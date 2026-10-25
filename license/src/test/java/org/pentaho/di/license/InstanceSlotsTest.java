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

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Slots in one JVM: each claim stands for a process. {@link InstanceSlotsProcessTest} does the same with real separate
 * processes.
 */
public class InstanceSlotsTest {

  @Rule
  public TemporaryFolder temp = new TemporaryFolder();

  private final List<InstanceSlots.Claim> claims = new ArrayList<>();
  private final UUID license = UUID.randomUUID();
  private Path folder;

  @Before
  public void setUp() {
    folder = temp.getRoot().toPath().resolve( "slots" );
  }

  @After
  public void releaseAll() {
    claims.forEach( InstanceSlots.Claim::release );
  }

  private static InstanceLimit hard( int max ) {
    return InstanceLimit.of( max, InstanceLimit.Counting.FOLDER, false );
  }

  private static InstanceLimit soft( int max ) {
    return InstanceLimit.of( max, InstanceLimit.Counting.FOLDER, true );
  }

  private InstanceSlots.Claim claim( InstanceLimit limit ) throws LicenseException {
    return claim( license, limit, 0 );
  }

  private InstanceSlots.Claim claim( UUID id, InstanceLimit limit, long waitMillis ) throws LicenseException {
    InstanceSlots.Claim c = InstanceSlots.claim( id, limit, folder, waitMillis );
    claims.add( c );
    return c;
  }

  private Path dir() {
    return folder.resolve( license.toString() );
  }

  private String usageText() throws Exception {
    StringBuilder all = new StringBuilder();
    try ( java.nio.file.DirectoryStream<Path> files = Files.newDirectoryStream( dir(), "usage-*.tsv" ) ) {
      for ( Path f : files ) {
        all.append( new String( Files.readAllBytes( f ), StandardCharsets.UTF_8 ) );
      }
    }
    return all.toString();
  }

  @Test
  public void aHardLimitRefusesTheProcessOverIt() throws Exception {
    Set<Integer> slots = new HashSet<>();
    for ( int i = 0; i < 3; i++ ) {
      slots.add( claim( hard( 3 ) ).getSlot() );
    }
    assertEquals( Set.of( 1, 2, 3 ), slots );
    try {
      claim( hard( 3 ) );
      fail( "expected a refusal" );
    } catch ( LicenseException e ) {
      assertTrue( e.getMessage(), e.getMessage().startsWith( "All 3 PDI processes this license allows are running" ) );
      assertTrue( e.getMessage(), e.getMessage().contains( "license.sh usage" ) );
      assertTrue( e.getMessage(), e.getMessage().contains( InstanceSlots.WAIT_ENV ) );
      assertFalse( e.getMessage(), e.getMessage().contains( "across machines" ) );
    }
    InstanceSlots.Claim first = claims.get( 0 );
    first.release();
    assertEquals( first.getSlot(), claim( hard( 3 ) ).getSlot() );
  }

  @Test
  public void releasingClearsTheRecordAndRecordsTheStop() throws Exception {
    InstanceSlots.Claim c = claim( hard( 1 ) );
    Path slot = dir().resolve( InstanceSlots.slotName( 1 ) );
    c.release();
    assertEquals( 0, Files.size( slot ) );
    String usage = usageText();
    assertTrue( usage, usage.contains( "\tstart\t1\t" ) );
    assertTrue( usage, usage.contains( "\tstop\t1\t" ) );
  }

  @Test
  public void aSoftLimitLetsTheProcessOverItRunWithAWarning() throws Exception {
    InstanceSlots.Claim inside = claim( soft( 1 ) );
    assertFalse( inside.getWarning().isPresent() );
    InstanceSlots.Claim over = claim( soft( 1 ) );
    assertTrue( over.isOverLimit() );
    assertTrue( over.getWarning().get(), over.getWarning().get().startsWith( "Over the license limit: The one PDI process"
      + " this license allows is running" ) );
    assertEquals( 1, countFiles( InstanceSlots.OVER_PREFIX ) );
    over.release();
    assertEquals( 0, countFiles( InstanceSlots.OVER_PREFIX ) );
    InstanceSlots.Month month = InstanceSlots.usage( license, folder, System.currentTimeMillis() ).months.get( 0 );
    assertEquals( 1, month.starts );
    assertEquals( 1, month.over );
  }

  /** A slot whose lock is free but whose record names a live process elsewhere: locks aren't shared, it's taken. */
  @Test
  public void aSlotNamingAnotherLiveProcessIsTaken() throws Exception {
    Files.createDirectories( dir() );
    long now = System.currentTimeMillis();
    Files.write( dir().resolve( InstanceSlots.slotName( 1 ) ),
      new InstanceSlots.Record( "other", "node-7", 42, now - 5000, now - 1000 ).line().getBytes( StandardCharsets.UTF_8 ) );
    try {
      claim( hard( 1 ) );
      fail( "expected a refusal" );
    } catch ( LicenseException e ) {
      assertTrue( e.getMessage(), e.getMessage().contains( "either it ended without giving its slot back" ) );
      assertTrue( e.getMessage(), e.getMessage().contains( "file locks in this folder don't work across machines" ) );
    }
  }

  /** A crash on this host is recognised at once: its pid is gone, or is this process's own (a restarted container). */
  @Test
  public void aFreshRecordOfAnEndedProcessOnThisHostIsNoObstacle() throws Exception {
    Files.createDirectories( dir() );
    InstanceSlots.Record me = InstanceSlots.Record.ofThisProcess();
    Process ended = new ProcessBuilder( "true" ).start();
    ended.waitFor();
    for ( long pid : new long[] { ended.pid(), me.pid } ) {
      long now = System.currentTimeMillis();
      Files.write( dir().resolve( InstanceSlots.slotName( 1 ) ), new InstanceSlots.Record( "before", me.host, pid, now,
        now ).line().getBytes( StandardCharsets.UTF_8 ) );
      InstanceSlots.Claim c = claim( hard( 1 ) );
      assertEquals( 1, c.getSlot() );
      c.release();
    }
  }

  @Test
  public void aStaleRecordIsNoObstacle() throws Exception {
    Files.createDirectories( dir() );
    long longAgo = System.currentTimeMillis() - 2 * InstanceSlots.STALE_MILLIS;
    Files.write( dir().resolve( InstanceSlots.slotName( 1 ) ),
      new InstanceSlots.Record( "crashed", "node-7", 42, longAgo, longAgo ).line().getBytes( StandardCharsets.UTF_8 ) );
    assertEquals( 1, claim( hard( 1 ) ).getSlot() );
  }

  @Test
  public void waitsForASlotToFreeUp() throws Exception {
    InstanceSlots.Claim holder = claim( hard( 1 ) );
    Thread releaser = new Thread( () -> {
      try {
        Thread.sleep( 500 );
      } catch ( InterruptedException e ) {
        Thread.currentThread().interrupt();
      }
      holder.release();
    } );
    releaser.start();
    long start = System.currentTimeMillis();
    assertEquals( 1, claim( license, hard( 1 ), 20_000 ).getSlot() );
    assertTrue( System.currentTimeMillis() - start < 15_000 );
    releaser.join();
  }

  @Test
  public void aFolderThatCantBeUsed() throws Exception {
    Files.write( folder, new byte[ 0 ] );   // a file where the folder should be
    try {
      claim( hard( 2 ) );
      fail( "expected a refusal" );
    } catch ( LicenseException e ) {
      assertTrue( e.getMessage(), e.getMessage().startsWith( "Can't count PDI processes in " + dir() ) );
      assertTrue( e.getMessage(), e.getMessage().contains( InstanceSlots.FOLDER_ENV ) );
    }
    InstanceSlots.Claim uncounted = claim( soft( 2 ) );
    assertTrue( uncounted.isOverLimit() );
    assertTrue( uncounted.getWarning().get(), uncounted.getWarning().get().endsWith( "this process runs uncounted." ) );
  }

  @Test
  public void twoLicensesInOneFolderDontShareSlots() throws Exception {
    assertEquals( 1, claim( license, hard( 1 ), 0 ).getSlot() );
    assertEquals( 1, claim( UUID.randomUUID(), hard( 1 ), 0 ).getSlot() );
  }

  /** The sampler counts every live record (its own from memory); the report gives the peak and the starts. */
  @Test
  public void usageShowsWhoRunsAndThePeak() throws Exception {
    InstanceSlots.Claim mine = claim( hard( 3 ) );
    long now = System.currentTimeMillis();
    int other = mine.getSlot() == 3 ? 2 : 3;
    Files.write( dir().resolve( InstanceSlots.slotName( other ) ),
      new InstanceSlots.Record( "other", "node-7", 42, now, now ).line().getBytes( StandardCharsets.UTF_8 ) );
    Files.write( dir().resolve( InstanceSlots.OVER_PREFIX + "x" ),
      new InstanceSlots.Record( "x", "node-8", 43, now, now ).line().getBytes( StandardCharsets.UTF_8 ) );
    mine.beat();   // takes the sampler lock and samples

    InstanceSlots.Usage usage = InstanceSlots.usage( license, folder, System.currentTimeMillis() );
    assertEquals( 1, usage.months.size() );
    assertEquals( 3, usage.months.get( 0 ).peak );
    assertEquals( 1, usage.months.get( 0 ).starts );
    // this JVM's own slot isn't opened (that could release its lock), so only the other two are listed here
    assertEquals( 2, usage.running.size() );
    assertEquals( other, usage.running.get( 0 ).slot );
    assertEquals( "node-7", usage.running.get( 0 ).host );
    assertEquals( 0, usage.running.get( 1 ).slot );
  }

  private long countFiles( String prefix ) throws Exception {
    try ( java.util.stream.Stream<Path> files = Files.list( dir() ) ) {
      return files.filter( f -> f.getFileName().toString().startsWith( prefix ) ).count();
    }
  }
}
