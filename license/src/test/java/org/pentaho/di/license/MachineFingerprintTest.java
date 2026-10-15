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

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class MachineFingerprintTest {

  private static final UUID LICENSE = UUID.randomUUID();
  private static final List<String> NICS = Arrays.asList( "001122334455", "001122334466" );

  private static MachineFingerprint fp( String os, String machineId, String host, List<String> macs ) {
    return MachineFingerprint.of( LICENSE, new MachineInfo( os, machineId, host, macs ) );
  }

  private static final MachineFingerprint ACTIVATED = fp( "linux", "abc123", "etl-01", NICS );

  @Test
  public void theSameMachineMatches() {
    assertNull( ACTIVATED.mismatch( fp( "linux", "abc123", "etl-01", NICS ) ) );
  }

  @Test
  public void aReplacedNetworkCardIsTolerated() {
    assertTrue( ACTIVATED.matches( fp( "linux", "abc123", "etl-01", Collections.singletonList( "00aabbccddee" ) ) ) );
  }

  @Test
  public void aRenamedHostIsTolerated() {
    assertTrue( ACTIVATED.matches( fp( "linux", "abc123", "etl-02", NICS ) ) );
  }

  @Test
  public void aRenamedHostWithNewNetworkCardsIsNot() {
    // the same machine id alone could be a cloned disk image
    assertNotNull( ACTIVATED.mismatch( fp( "linux", "abc123", "etl-02", Collections.singletonList( "00aabbccddee" ) ) ) );
  }

  @Test
  public void aDifferentMachineIdIsAnotherMachine() {
    assertFalse( ACTIVATED.matches( fp( "linux", "zzz999", "etl-01", NICS ) ) );
  }

  @Test
  public void aDifferentOperatingSystemIsAnotherMachine() {
    assertFalse( ACTIVATED.matches( fp( "windows", "abc123", "etl-01", NICS ) ) );
  }

  @Test
  public void withoutAMachineIdTheHostAndANetworkCardMustMatch() {
    MachineFingerprint noId = fp( "linux", null, "etl-01", NICS );
    assertTrue( noId.matches( fp( "linux", null, "etl-01", Collections.singletonList( "001122334455" ) ) ) );
    assertFalse( noId.matches( fp( "linux", null, "etl-02", NICS ) ) );
    assertFalse( noId.matches( fp( "linux", null, "etl-01", Collections.singletonList( "00aabbccddee" ) ) ) );
  }

  @Test
  public void hostNamesAreComparedWithoutDomainOrCase() {
    assertTrue( fp( "mac", null, "Build-01.local", NICS ).matches( fp( "mac", null, "build-01", NICS ) ) );
  }

  @Test
  public void fingerprintsOfOneMachineDifferPerLicense() {
    MachineInfo info = new MachineInfo( "linux", "abc123", "etl-01", NICS );
    MachineFingerprint a = MachineFingerprint.of( UUID.randomUUID(), info );
    MachineFingerprint b = MachineFingerprint.of( UUID.randomUUID(), info );
    assertNotEquals( a, b );
    assertFalse( a.matches( b ) );
  }

  @Test
  public void textFormRoundTrips() throws Exception {
    assertEquals( ACTIVATED, MachineFingerprint.decode( ACTIVATED.encode() ) );
    MachineFingerprint sparse = fp( "linux", null, null, Collections.emptyList() );
    assertEquals( sparse, MachineFingerprint.decode( sparse.encode() ) );
  }

  @Test
  public void randomizedAndEmptyMacAddressesAreNotHardware() {
    assertNull( MachineInfo.physicalMac( null ) );
    assertNull( MachineInfo.physicalMac( new byte[ 6 ] ) );
    assertNull( MachineInfo.physicalMac( new byte[] { 0x02, 0x42, (byte) 0xac, 0x11, 0, 2 } ) );   // docker
    assertEquals( "001a2b3c4d5e", MachineInfo.physicalMac( new byte[] { 0, 0x1a, 0x2b, 0x3c, 0x4d, 0x5e } ) );
  }

  @Test
  public void aContainerWithoutMachineIdOrHardwareCardsIsNotStable() {
    assertFalse( fp( "linux", null, "3f2a9c0d1b2e", Collections.emptyList() ).isStable() );
    assertTrue( fp( "linux", "abc123", "etl-01", Collections.emptyList() ).isStable() );
    assertTrue( fp( "linux", null, "etl-01", NICS ).isStable() );
  }

  @Test
  public void thisMachineHasAFingerprintThatMatchesItself() {
    MachineFingerprint here = MachineFingerprint.ofThisMachine( LICENSE );
    assertTrue( here.describe(), here.matches( MachineFingerprint.ofThisMachine( LICENSE ) ) );
    assertTrue( here.describe(), here.isStable() );
  }
}
