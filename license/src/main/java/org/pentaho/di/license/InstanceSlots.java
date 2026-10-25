/*! ******************************************************************************
 *
 * Pentaho Data Integration
 *
 * Copyright (C) 2026 by Hitachi Vantara : http://www.pentaho.com
 *
 *******************************************************************************
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 ******************************************************************************/

package org.pentaho.di.license;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

/**
 * Counts the PDI processes of a license limited to N at once ({@link InstanceLimit.Counting#FOLDER}, tier 2 of
 * {@code docs/licensing/instance-limits.md}): each process holds one of N slot files in a folder they all share,
 * {@code <folder>/<license id>/slot-0001} to {@code slot-N}, for as long as it runs.
 * <p>
 * A slot is held by an exclusive lock on its file, which the operating system (or the file server) releases when
 * the process ends, however it ends, so a crash never leaks a slot. The process also writes its record into the slot
 * and refreshes a heartbeat in it: a slot whose lock is free but which names another process with a fresh heartbeat
 * is taken all the same, which is how a folder whose locks don't work across machines (NFS with {@code nolock}) is
 * noticed instead of quietly counting nothing.
 * <p>
 * Starts, stops, and starts over the limit are appended to {@code usage-YYYY-MM.tsv} next to the slots; one process
 * at a time (whichever holds {@code sampler.lock}) also records how many processes are running, for
 * {@link #usage(UUID, Path, long)}.
 * <p>
 * Closing a channel can release every lock the JVM holds on that file, so this class never opens a file whose slot
 * this JVM holds ({@link #HELD}).
 */
public final class InstanceSlots {

  public static final String FOLDER_PROPERTY = "helix.license.slots";
  public static final String FOLDER_ENV = "HELIX_LICENSE_SLOTS";
  public static final String WAIT_PROPERTY = "helix.license.wait";
  public static final String WAIT_ENV = "HELIX_LICENSE_WAIT";
  static final String DEFAULT_FOLDER = ".helix-slots";

  static final long HEARTBEAT_MILLIS = 20_000L;
  /** A record whose heartbeat is older than this belongs to a process that has ended. */
  static final long STALE_MILLIS = 60_000L;
  /** The sampler records the number running when it changes, and at least this often. */
  static final long SAMPLE_EVERY_MILLIS = 15 * 60_000L;

  static final String SLOT_PREFIX = "slot-";
  static final String OVER_PREFIX = "over-";
  static final String USAGE_PREFIX = "usage-";
  static final String SAMPLER_LOCK = "sampler.lock";

  /** Slot files this JVM holds the lock of: never opened again here (see the class comment). */
  static final Set<Path> HELD = ConcurrentHashMap.newKeySet();

  private static Claim held;   // this JVM's claim, guarded by InstanceSlots.class
  /** Serializes this JVM's usage lines; its own lock, as the heartbeat writes them holding its claim's monitor. */
  private static final Object USAGE_LOCK = new Object();

  private InstanceSlots() {
  }

  /**
   * Counts this process for {@code license} (once per JVM), as {@link LicenseEnforcement#requireLicensed()} does for
   * a license counted in a folder.
   *
   * @param bound whether the activation is bound to this machine (then the folder defaults to the Kettle folder's)
   * @throws LicenseException when no folder is configured for an unbound activation, or under a hard limit when no
   *                          slot is free (after {@value #WAIT_ENV} seconds) or the folder can't be used
   */
  static synchronized Claim take( LicensePayload license, boolean bound ) throws LicenseException {
    if ( held != null && held.licenseId.equals( license.getLicenseId() ) ) {
      return held;
    }
    InstanceLimit limit = license.getInstanceLimit();
    Path folder = folder( bound );
    if ( folder == null ) {
      throw new LicenseException( "This license allows " + limit.getMax() + " PDI processes at once, counted in a"
        + " folder they all share, and is installed as an unbound activation (containers): set " + FOLDER_ENV
        + " (or -D" + FOLDER_PROPERTY + ") to that folder, mounted in every container." );
    }
    Claim claim = claim( license.getLicenseId(), limit, folder, waitMillis() );
    if ( held != null ) {
      held.release();
    }
    held = claim;
    claim.keepAlive();
    Runtime.getRuntime().addShutdownHook( new Thread( claim::release, "PDI license slot release" ) );
    return claim;
  }

  /** This process's claim, if it took one. */
  public static synchronized Optional<Claim> current() {
    return Optional.ofNullable( held );
  }

  /** For tests: forget this JVM's claim, releasing it. */
  static synchronized void reset() {
    if ( held != null ) {
      held.release();
      held = null;
    }
  }

  /**
   * The folder slots are kept in: {@value #FOLDER_ENV} or {@code -D}{@value #FOLDER_PROPERTY}; without one, the
   * Kettle folder's {@value #DEFAULT_FOLDER} for an activation bound to this machine, and null for an unbound one
   * (a folder inside one container would count that container alone).
   */
  public static Path folder( boolean bound ) {
    String configured = LicenseStore.firstNonBlank( System.getProperty( FOLDER_PROPERTY ), System.getenv( FOLDER_ENV ) );
    if ( configured != null ) {
      return Paths.get( configured );
    }
    return bound ? Paths.get( LicenseStore.kettleDirectory(), DEFAULT_FOLDER ) : null;
  }

  /** How long to wait for a free slot: {@value #WAIT_ENV} seconds (0 when unset or not a number). */
  static long waitMillis() {
    String text = LicenseStore.firstNonBlank( System.getProperty( WAIT_PROPERTY ), System.getenv( WAIT_ENV ) );
    try {
      return text == null ? 0 : Math.max( 0, Long.parseLong( text ) ) * 1000;
    } catch ( NumberFormatException e ) {
      return 0;
    }
  }

  /**
   * Takes a free slot in {@code folder}, waiting up to {@code waitMillis} for one. Under a soft limit, no free slot
   * (or a folder that can't be used) gives a claim over the limit, with a warning, instead of an exception.
   */
  static Claim claim( UUID licenseId, InstanceLimit limit, Path folder, long waitMillis ) throws LicenseException {
    Path dir = folder.resolve( licenseId.toString() );
    Record me = Record.ofThisProcess();
    long deadline = System.currentTimeMillis() + waitMillis;
    int notShared;
    try {
      Files.createDirectories( dir );
      while ( true ) {
        Attempt attempt = attempt( dir, limit.getMax(), me );
        if ( attempt.slot > 0 ) {
          Claim claim = new Claim( licenseId, limit, dir, attempt.slot, me, attempt.channel, attempt.lock, null );
          appendUsage( dir, "start\t" + attempt.slot + "\t" + me.usageColumns() );
          return claim;
        }
        notShared = attempt.notShared;
        long left = deadline - System.currentTimeMillis();
        if ( left <= 0 ) {
          break;
        }
        Thread.sleep( Math.min( left, ThreadLocalRandom.current().nextLong( 2000, 5000 ) ) );
      }
    } catch ( IOException | RuntimeException e ) {
      String problem = "Can't count PDI processes in " + dir + " (" + e.getMessage() + ")";
      if ( limit.isSoft() ) {
        return new Claim( licenseId, limit, dir, 0, me, null, null, problem + ": this process runs uncounted." );
      }
      throw new LicenseException( problem + ". This license allows " + limit.getMax() + " PDI processes at once,"
        + " counted in a folder they all share: check that " + folder + " is mounted and writable (" + FOLDER_ENV
        + ").", e );
    } catch ( InterruptedException e ) {
      Thread.currentThread().interrupt();
      throw new LicenseException( "Interrupted while waiting for a free PDI process slot" );
    }

    String full = ( limit.getMax() == 1 ? "The one PDI process this license allows is"
      : "All " + limit.getMax() + " PDI processes this license allows are" ) + " running (slots in " + dir + ")"
      + ( notShared == 0 ? "" : "; " + notShared + " of the slots are unlocked but name a process on another machine"
      + " that was alive less than a minute ago: either it ended without giving its slot back (the slot is free a"
      + " minute after its last heartbeat), or file locks in this folder don't work across machines (an NFS mount"
      + " with 'nolock' or 'local_lock'?)" );
    if ( limit.isSoft() ) {
      Claim over = new Claim( licenseId, limit, dir, 0, me, null, null, "Over the license limit: " + full + ". This"
        + " process runs anyway (the limit is soft) and is recorded as over the limit." );
      over.writeOverRecord();
      appendUsage( dir, "over\t0\t" + me.usageColumns() );
      return over;
    }
    throw new LicenseException( full + ". Run './license.sh usage' to see which processes hold them"
      + ( waitMillis == 0 ? "; set " + WAIT_ENV + "=<seconds> to wait for one to finish." : "; none finished within "
      + waitMillis / 1000 + " seconds." ) );
  }

  private static final class Attempt {
    int slot;
    FileChannel channel;
    FileLock lock;
    int notShared;
  }

  /** One pass over the slots, in random order: the first one free is taken. */
  private static Attempt attempt( Path dir, int max, Record me ) throws IOException {
    List<Integer> order = new ArrayList<>( max );
    for ( int i = 1; i <= max; i++ ) {
      order.add( i );
    }
    Collections.shuffle( order );
    Attempt attempt = new Attempt();
    for ( int slot : order ) {
      Path file = dir.resolve( slotName( slot ) );
      if ( HELD.contains( file.toAbsolutePath() ) ) {
        continue;
      }
      FileChannel channel = FileChannel.open( file, CREATE, READ, WRITE );
      FileLock lock = null;
      try {
        lock = channel.tryLock();
      } catch ( OverlappingFileLockException e ) {
        // held in this JVM through another channel
      }
      if ( lock == null ) {
        channel.close();
        continue;
      }
      Record previous = Record.parse( read( channel ) );
      if ( previous != null && !previous.instance.equals( me.instance )
        && previous.isFresh( System.currentTimeMillis() ) && !previous.hasEndedOnThisHost( me ) ) {
        attempt.notShared++;
        lock.release();
        channel.close();
        continue;
      }
      HELD.add( file.toAbsolutePath() );
      write( channel, me.withHeartbeat( System.currentTimeMillis() ) );
      attempt.slot = slot;
      attempt.channel = channel;
      attempt.lock = lock;
      return attempt;
    }
    return attempt;
  }

  static String slotName( int slot ) {
    return String.format( "%s%04d", SLOT_PREFIX, slot );
  }

  /** A process holding a slot, or running over the limit. */
  public static final class Claim {
    private final UUID licenseId;
    private final InstanceLimit limit;
    private final Path dir;
    private final int slot;
    private final Record record;
    private final FileChannel channel;
    private final FileLock lock;
    private final String warning;
    private ScheduledExecutorService heartbeat;
    private FileChannel samplerChannel;
    private FileLock samplerLock;
    private int lastSample = -1;
    private long lastSampleAt;
    private boolean released;

    Claim( UUID licenseId, InstanceLimit limit, Path dir, int slot, Record record, FileChannel channel, FileLock lock,
           String warning ) {
      this.licenseId = licenseId;
      this.limit = limit;
      this.dir = dir;
      this.slot = slot;
      this.record = record;
      this.channel = channel;
      this.lock = lock;
      this.warning = warning;
    }

    /** The slot held (1 to the limit), or 0 when running over the limit. */
    public int getSlot() {
      return slot;
    }

    public boolean isOverLimit() {
      return slot == 0;
    }

    /** What to log as an error: running over a soft limit, or uncounted. */
    public Optional<String> getWarning() {
      return Optional.ofNullable( warning );
    }

    /** {@code PDI process slot 12 of 300 (folder /mnt/pdi-slots/<license id>)}. */
    public String describe() {
      return slot > 0 ? "PDI process slot " + slot + " of " + limit.getMax() + " (folder " + dir + ")"
        : "PDI process over the limit of " + limit.getMax() + " (folder " + dir + ")";
    }

    /** Starts the heartbeat (which also samples), at once and then every {@value #HEARTBEAT_MILLIS} ms. */
    synchronized void keepAlive() {
      if ( heartbeat != null || released ) {
        return;
      }
      heartbeat = Executors.newSingleThreadScheduledExecutor( r -> {
        Thread t = new Thread( r, "PDI license slot heartbeat" );
        t.setDaemon( true );
        return t;
      } );
      heartbeat.scheduleWithFixedDelay( this::beat, 0, HEARTBEAT_MILLIS, TimeUnit.MILLISECONDS );
    }

    synchronized void beat() {
      if ( released ) {
        return;
      }
      long now = System.currentTimeMillis();
      try {
        if ( channel != null ) {
          write( channel, record.withHeartbeat( now ) );
        } else {
          writeOverRecord();
        }
      } catch ( IOException | RuntimeException e ) {
        // a missed heartbeat never stops a running process
      }
      sample( now );
    }

    void writeOverRecord() {
      try {
        Files.write( overFile(), record.withHeartbeat( System.currentTimeMillis() ).line().getBytes(
          StandardCharsets.UTF_8 ) );
      } catch ( IOException | RuntimeException e ) {
        // the folder can't be used: this process is uncounted, as its warning says
      }
    }

    private Path overFile() {
      return dir.resolve( OVER_PREFIX + record.instance );
    }

    /** If this process is the sampler (holds the sampler lock), records how many are running when it changed. */
    private void sample( long now ) {
      try {
        if ( samplerLock == null ) {
          FileChannel ch = FileChannel.open( dir.resolve( SAMPLER_LOCK ), CREATE, WRITE );
          FileLock l = null;
          try {
            l = ch.tryLock();
          } catch ( OverlappingFileLockException e ) {
            // another claim in this JVM samples
          }
          if ( l == null ) {
            ch.close();
            return;
          }
          samplerChannel = ch;
          samplerLock = l;
        }
        int running = running( dir, now, this ).size();
        if ( running != lastSample || now - lastSampleAt >= SAMPLE_EVERY_MILLIS ) {
          appendUsage( dir, "sample\t" + running );
          lastSample = running;
          lastSampleAt = now;
        }
      } catch ( IOException | RuntimeException e ) {
        // sampling is for the usage report only
      }
    }

    /** Gives the slot back: clears the record, records the stop, releases the lock. Run by a shutdown hook. */
    synchronized void release() {
      if ( released ) {
        return;
      }
      released = true;
      if ( heartbeat != null ) {
        heartbeat.shutdownNow();
      }
      try {
        if ( channel != null ) {
          channel.truncate( 0 );
          channel.force( false );
        } else {
          Files.deleteIfExists( overFile() );
        }
      } catch ( IOException | RuntimeException e ) {
        // the lock still goes with the process; a stale record is ignored after STALE_MILLIS
      }
      appendUsage( dir, "stop\t" + slot + "\t" + record.usageColumns() );
      try {
        if ( samplerChannel != null ) {
          samplerChannel.close();
        }
        if ( channel != null ) {
          lock.release();
          channel.close();
        }
      } catch ( IOException | RuntimeException e ) {
        // closing on the way out
      }
      HELD.remove( dir.resolve( slotName( slot ) ).toAbsolutePath() );
    }
  }

  /** What a slot (or over-the-limit) file says about its process: one tab-separated line. */
  static final class Record {
    final String instance;
    final String host;
    final long pid;
    final long startedAt;
    final long heartbeat;

    Record( String instance, String host, long pid, long startedAt, long heartbeat ) {
      this.instance = instance;
      this.host = host;
      this.pid = pid;
      this.startedAt = startedAt;
      this.heartbeat = heartbeat;
    }

    static Record ofThisProcess() {
      long now = System.currentTimeMillis();
      return new Record( UUID.randomUUID().toString(), hostName(), ProcessHandle.current().pid(), now, now );
    }

    Record withHeartbeat( long time ) {
      return new Record( instance, host, pid, startedAt, time );
    }

    /**
     * Whether this record's process has certainly ended: it was on this host ({@code me}'s), and its pid is gone, or
     * is {@code me}'s own (a container restarted, as pid 1 again).
     */
    boolean hasEndedOnThisHost( Record me ) {
      if ( !host.equals( me.host ) ) {
        return false;
      }
      return pid == me.pid || !ProcessHandle.of( pid ).map( ProcessHandle::isAlive ).orElse( false );
    }

    /** Whether the process was alive within {@value #STALE_MILLIS} ms of {@code now} (either way: clocks differ). */
    boolean isFresh( long now ) {
      return Math.abs( now - heartbeat ) < STALE_MILLIS;
    }

    String line() {
      return instance + "\t" + host + "\t" + pid + "\t" + startedAt + "\t" + heartbeat + "\n";
    }

    String usageColumns() {
      return instance + "\t" + host + "\t" + pid;
    }

    /** null for an empty, cleared or unreadable record */
    static Record parse( String text ) {
      String[] f = text == null ? new String[ 0 ] : text.trim().split( "\t" );
      if ( f.length != 5 ) {
        return null;
      }
      try {
        return new Record( f[ 0 ], f[ 1 ], Long.parseLong( f[ 2 ] ), Long.parseLong( f[ 3 ] ),
          Long.parseLong( f[ 4 ] ) );
      } catch ( NumberFormatException e ) {
        return null;
      }
    }

    private static String hostName() {
      String name = LicenseStore.firstNonBlank( System.getenv( "HOSTNAME" ), System.getenv( "COMPUTERNAME" ) );
      if ( name == null ) {
        try {
          name = java.net.InetAddress.getLocalHost().getHostName();
        } catch ( IOException | RuntimeException e ) {
          name = "unknown";
        }
      }
      return name.replaceAll( "\\s+", "_" );
    }
  }

  private static String read( FileChannel channel ) throws IOException {
    ByteBuffer buffer = ByteBuffer.allocate( 1024 );
    int n = channel.read( buffer, 0 );
    return n <= 0 ? "" : new String( buffer.array(), 0, n, StandardCharsets.UTF_8 );
  }

  private static void write( FileChannel channel, Record record ) throws IOException {
    channel.truncate( 0 );
    channel.write( ByteBuffer.wrap( record.line().getBytes( StandardCharsets.UTF_8 ) ), 0 );
    // through to the file server, so other machines see the heartbeat
    channel.force( false );
  }

  /**
   * Appends a line ({@code time, event, ...}) to this month's usage file, under a lock on it so lines from processes
   * writing at once don't mix. Best effort: usage records never stop a process.
   */
  static void appendUsage( Path dir, String line ) {
    Path file = dir.resolve( USAGE_PREFIX + YearMonth.now( ZoneOffset.UTC ) + ".tsv" );
    synchronized ( USAGE_LOCK ) {
      try ( FileChannel ch = FileChannel.open( file, CREATE, WRITE, APPEND ) ) {
        FileLock lock = null;
        for ( int i = 0; i < 20 && lock == null; i++ ) {
          lock = ch.tryLock();
          if ( lock == null ) {
            Thread.sleep( 50 );
          }
        }
        ch.write( ByteBuffer.wrap( ( Instant.now() + "\t" + line + "\n" ).getBytes( StandardCharsets.UTF_8 ) ) );
      } catch ( IOException | RuntimeException e ) {
        // best effort
      } catch ( InterruptedException e ) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /** A process running now, as its slot or over-the-limit file says. */
  public static final class Running {
    /** 0: over the limit */
    public final int slot;
    public final String host;
    public final long pid;
    public final long startedAt;
    public final long heartbeat;

    Running( int slot, Record r ) {
      this.slot = slot;
      this.host = r.host;
      this.pid = r.pid;
      this.startedAt = r.startedAt;
      this.heartbeat = r.heartbeat;
    }
  }

  /**
   * The processes whose records are fresh, read without taking any lock (so it never gets in the way of a process
   * starting); {@code self}'s own slot is counted from memory, not opened.
   */
  static List<Running> running( Path dir, long now, Claim self ) throws IOException {
    List<Running> out = new ArrayList<>();
    if ( !Files.isDirectory( dir ) ) {
      return out;
    }
    try ( DirectoryStream<Path> files = Files.newDirectoryStream( dir ) ) {
      for ( Path f : files ) {
        String name = f.getFileName().toString();
        boolean isSlot = name.startsWith( SLOT_PREFIX );
        if ( !isSlot && !name.startsWith( OVER_PREFIX ) ) {
          continue;
        }
        int slot = isSlot ? parseSlot( name ) : 0;
        if ( HELD.contains( f.toAbsolutePath() ) ) {
          if ( self != null && self.slot == slot ) {
            out.add( new Running( slot, self.record.withHeartbeat( now ) ) );
          }
          continue;
        }
        Record r;
        try {
          r = Record.parse( new String( Files.readAllBytes( f ), StandardCharsets.UTF_8 ) );
        } catch ( IOException e ) {
          continue;
        }
        if ( r != null && r.isFresh( now ) ) {
          out.add( new Running( slot, r ) );
        }
      }
    }
    out.sort( ( a, b ) -> Integer.compare( a.slot == 0 ? Integer.MAX_VALUE : a.slot,
      b.slot == 0 ? Integer.MAX_VALUE : b.slot ) );
    return out;
  }

  private static int parseSlot( String name ) {
    try {
      return Integer.parseInt( name.substring( SLOT_PREFIX.length() ) );
    } catch ( NumberFormatException e ) {
      return -1;
    }
  }

  /** One month of the usage records. */
  public static final class Month {
    public final String month;
    /** the most processes running at once, as sampled; 0 when nothing was sampled */
    public int peak;
    public String peakAt;
    public int starts;
    public int over;

    Month( String month ) {
      this.month = month;
    }
  }

  /** What {@code license usage} shows: the processes running now and the usage records per month. */
  public static final class Usage {
    public final Path dir;
    public final List<Running> running;
    public final List<Month> months;

    Usage( Path dir, List<Running> running, List<Month> months ) {
      this.dir = dir;
      this.running = running;
      this.months = months;
    }
  }

  public static Usage usage( UUID licenseId, Path folder, long now ) throws IOException {
    Path dir = folder.resolve( licenseId.toString() );
    List<Month> months = new ArrayList<>();
    if ( Files.isDirectory( dir ) ) {
      List<Path> files = new ArrayList<>();
      try ( DirectoryStream<Path> found = Files.newDirectoryStream( dir, USAGE_PREFIX + "*.tsv" ) ) {
        found.forEach( files::add );
      }
      Collections.sort( files );
      for ( Path f : files ) {
        String name = f.getFileName().toString();
        Month m = new Month( name.substring( USAGE_PREFIX.length(), name.length() - ".tsv".length() ) );
        for ( String line : Files.readAllLines( f, StandardCharsets.UTF_8 ) ) {
          String[] c = line.split( "\t" );
          if ( c.length < 3 ) {
            continue;
          }
          switch ( c[ 1 ] ) {
            case "start":
              m.starts++;
              break;
            case "over":
              m.over++;
              break;
            case "sample":
              try {
                int n = Integer.parseInt( c[ 2 ] );
                if ( n > m.peak ) {
                  m.peak = n;
                  m.peakAt = c[ 0 ];
                }
              } catch ( NumberFormatException e ) {
                // a damaged line
              }
              break;
            default:
              break;
          }
        }
        months.add( m );
      }
    }
    return new Usage( dir, running( dir, now, null ), months );
  }
}
