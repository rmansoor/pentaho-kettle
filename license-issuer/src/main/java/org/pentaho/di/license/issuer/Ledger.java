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

package org.pentaho.di.license.issuer;

import org.pentaho.di.license.LicenseException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The issuer's record of every license key and activation: a tab-separated text file, appended to and never
 * rewritten, so it can be read, grepped and kept in version control or a backup like any other file.
 * <pre>
 * time  event  license_id  activation_id  customer  edition  binding  note
 * </pre>
 * Events: {@code issued} (a license key), {@code activated} (binding = the machine fingerprint, or
 * {@code unbound}), {@code released} (an activation given back: release code, replaced on the same machine, or
 * released by the issuer).
 * <p>
 * A license can have one activation at a time: activations of a license id minus those released.
 */
public final class Ledger {

  public static final String HEADER = "time\tevent\tlicense_id\tactivation_id\tcustomer\tedition\tbinding\tnote";

  public static final class Entry {
    public final String time;
    public final String event;
    public final String licenseId;
    public final String activationId;
    public final String customer;
    public final String edition;
    public final String binding;
    public final String note;

    public Entry( String time, String event, String licenseId, String activationId, String customer, String edition,
           String binding, String note ) {
      this.time = time;
      this.event = event;
      this.licenseId = licenseId;
      this.activationId = activationId;
      this.customer = customer;
      this.edition = edition;
      this.binding = binding;
      this.note = note;
    }

    String line() {
      return String.join( "\t", time, event, licenseId, activationId, clean( customer ), clean( edition ), binding,
        clean( note ) );
    }
  }

  private static final ConcurrentMap<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

  private final Path path;

  public Ledger( Path path ) {
    this.path = path;
  }

  public Path path() {
    return path;
  }

  public List<Entry> entries() throws IOException {
    if ( !Files.exists( path ) ) {
      return new ArrayList<>();
    }
    return parse( new String( Files.readAllBytes( path ), StandardCharsets.UTF_8 ) );
  }

  private List<Entry> parse( String text ) throws IOException {
    List<Entry> out = new ArrayList<>();
    for ( String line : text.split( "\r?\n" ) ) {
      if ( line.isEmpty() || line.startsWith( "time\t" ) || line.startsWith( "#" ) ) {
        continue;
      }
      String[] f = line.split( "\t", -1 );
      if ( f.length < 8 ) {
        throw new IOException( "Malformed ledger line in " + path + ": " + line );
      }
      out.add( new Entry( f[ 0 ], f[ 1 ], f[ 2 ], f[ 3 ], f[ 4 ], f[ 5 ], f[ 6 ], f[ 7 ] ) );
    }
    return out;
  }

  /** The activations of {@code licenseId} not released, oldest first. */
  public List<Entry> active( String licenseId ) throws IOException {
    return active( entries(), licenseId );
  }

  /** The activations of {@code licenseId} in {@code entries} not released, oldest first. */
  public static List<Entry> active( List<Entry> entries, String licenseId ) {
    Map<String, Entry> active = new LinkedHashMap<>();
    Set<String> released = new HashSet<>();
    for ( Entry e : entries ) {
      if ( !e.licenseId.equals( licenseId ) ) {
        continue;
      }
      if ( e.event.equals( "activated" ) ) {
        active.put( e.activationId, e );
      } else if ( e.event.equals( "released" ) ) {
        released.add( e.activationId );
      }
    }
    active.keySet().removeAll( released );
    return new ArrayList<>( active.values() );
  }

  public Entry find( String activationId ) throws IOException {
    return find( entries(), activationId );
  }

  /** The {@code activated} entry of {@code activationId} in {@code entries}, or null. */
  public static Entry find( List<Entry> entries, String activationId ) {
    for ( Entry e : entries ) {
      if ( e.event.equals( "activated" ) && e.activationId.equals( activationId ) ) {
        return e;
      }
    }
    return null;
  }

  /** What {@link #update} does with the ledger's entries, under its lock. */
  public interface Change<T> {
    /**
     * @param entries the ledger as it is now; add what to append to {@code append}
     * @return the result of {@link #update}
     */
    T apply( List<Entry> entries, List<Entry> append ) throws IOException, LicenseException;
  }

  /** Appends entries under an exclusive file lock, so two issuers sharing a ledger don't interleave. */
  public void append( Entry... entries ) throws IOException {
    try {
      update( ( current, append ) -> {
        Collections.addAll( append, entries );
        return null;
      } );
    } catch ( LicenseException e ) {
      throw new IllegalStateException( e );
    }
  }

  /**
   * Reads the ledger and appends what {@code change} decides, all under one exclusive file lock: a decision made on
   * the ledger's content (a license has no other activation) still holds when its entries are written, even with
   * several issuers (a web portal and the command line) sharing the file.
   */
  public <T> T update( Change<T> change ) throws IOException, LicenseException {
    if ( path.getParent() != null ) {
      Files.createDirectories( path.getParent() );
    }
    // a file lock is held by the whole JVM: threads of one process (a web portal's requests) queue here first
    ReentrantLock inProcess = LOCKS.computeIfAbsent( path.toAbsolutePath().normalize(), p -> new ReentrantLock() );
    inProcess.lock();
    try ( FileChannel ch = FileChannel.open( path, StandardOpenOption.CREATE, StandardOpenOption.READ,
      StandardOpenOption.WRITE ); FileLock lock = ch.lock() ) {
      ByteBuffer existing = ByteBuffer.allocate( (int) ch.size() );
      while ( existing.hasRemaining() && ch.read( existing ) >= 0 ) {
        // read it all
      }
      List<Entry> append = new ArrayList<>();
      T result = change.apply( parse( new String( existing.array(), StandardCharsets.UTF_8 ) ), append );
      if ( !append.isEmpty() ) {
        StringBuilder text = new StringBuilder();
        if ( ch.size() == 0 ) {
          text.append( HEADER ).append( '\n' );
        }
        for ( Entry e : append ) {
          text.append( e.line() ).append( '\n' );
        }
        ByteBuffer buffer = ByteBuffer.wrap( text.toString().getBytes( StandardCharsets.UTF_8 ) );
        ch.position( ch.size() );
        while ( buffer.hasRemaining() ) {
          ch.write( buffer );
        }
        ch.force( true );
      }
      return result;
    } finally {
      inProcess.unlock();
    }
  }

  public static String now() {
    return Instant.now().toString();
  }

  private static String clean( String s ) {
    return s == null ? "" : s.replaceAll( "[\\t\\r\\n]+", " " );
  }
}
