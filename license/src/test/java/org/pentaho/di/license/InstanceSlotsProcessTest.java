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
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Slots held by real, separate JVMs: what the operating system's file locks do between processes. */
public class InstanceSlotsProcessTest {

  @Rule
  public TemporaryFolder temp = new TemporaryFolder();

  private final List<Process> processes = new ArrayList<>();

  @After
  public void stopAll() {
    processes.forEach( Process::destroyForcibly );
  }

  /** A PDI process stand-in: takes a slot as KettleEnvironment would, says which, and runs until told to stop. */
  public static final class Holder {
    public static void main( String[] args ) throws Exception {
      LicensePayload license = new LicensePayload( UUID.fromString( args[ 0 ] ), "Acme Corp", "ce", 0, 0,
        InstanceLimit.of( Integer.parseInt( args[ 1 ] ), InstanceLimit.Counting.FOLDER, false ) );
      try {
        System.out.println( "slot " + InstanceSlots.take( license, false ).getSlot() );
      } catch ( LicenseException e ) {
        System.out.println( "refused " + e.getMessage() );
        System.exit( 1 );
      }
      System.out.flush();
      new BufferedReader( new InputStreamReader( System.in, StandardCharsets.UTF_8 ) ).readLine();
      System.exit( 0 );   // the shutdown hook gives the slot back
    }
  }

  private Process start( UUID license, int max, File folder ) throws Exception {
    Process p = new ProcessBuilder( System.getProperty( "java.home" ) + File.separator + "bin" + File.separator
      + "java", "-cp", System.getProperty( "java.class.path" ), "-D" + InstanceSlots.FOLDER_PROPERTY + "="
      + folder.getAbsolutePath(), Holder.class.getName(), license.toString(), Integer.toString( max ) )
      .redirectErrorStream( true ).start();
    processes.add( p );
    return p;
  }

  private static String firstLine( Process p ) throws Exception {
    BufferedReader out = new BufferedReader( new InputStreamReader( p.getInputStream(), StandardCharsets.UTF_8 ) );
    return CompletableFuture.supplyAsync( () -> {
      try {
        return out.readLine();
      } catch ( Exception e ) {
        return e.toString();
      }
    } ).get( 60, TimeUnit.SECONDS );
  }

  private static void stop( Process p ) throws Exception {
    OutputStream in = p.getOutputStream();
    in.write( '\n' );
    in.flush();
    assertTrue( p.waitFor( 30, TimeUnit.SECONDS ) );
  }

  @Test
  public void twoSlotsThreeProcesses() throws Exception {
    UUID license = UUID.randomUUID();
    File folder = temp.newFolder( "slots" );

    Process a = start( license, 2, folder );
    String lineA = firstLine( a );
    Process b = start( license, 2, folder );
    String lineB = firstLine( b );
    assertTrue( lineA, lineA.startsWith( "slot " ) );
    assertTrue( lineB, lineB.startsWith( "slot " ) );

    Process c = start( license, 2, folder );
    String lineC = firstLine( c );
    assertTrue( lineC, lineC.startsWith( "refused All 2 PDI processes this license allows are running" ) );
    assertTrue( c.waitFor( 30, TimeUnit.SECONDS ) );
    assertEquals( 1, c.exitValue() );

    // a clean exit gives the slot back at once
    stop( a );
    Process d = start( license, 2, folder );
    assertEquals( lineA, firstLine( d ) );

    // a killed process's lock goes with it, and on the same host its record is seen to be of an ended process
    int slotB = Integer.parseInt( lineB.substring( "slot ".length() ) );
    b.destroyForcibly().waitFor( 30, TimeUnit.SECONDS );
    Path file = folder.toPath().resolve( license.toString() ).resolve( InstanceSlots.slotName( slotB ) );
    try ( FileChannel ch = FileChannel.open( file, StandardOpenOption.READ, StandardOpenOption.WRITE ) ) {
      FileLock lock = ch.tryLock();
      assertNotNull( "the killed process's lock is released", lock );
      lock.release();
    }
    Process e = start( license, 2, folder );
    assertEquals( "slot " + slotB, firstLine( e ) );
    stop( d );
    stop( e );
  }
}
