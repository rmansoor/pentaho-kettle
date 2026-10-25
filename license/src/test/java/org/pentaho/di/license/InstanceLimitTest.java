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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** The instance limit in the license format: version 2 with a limit, version 1 byte for byte without one. */
public class InstanceLimitTest {

  private static final InstanceLimit FOLDER_300 = InstanceLimit.of( 300, InstanceLimit.Counting.FOLDER, false );

  private static LicensePayload payload( InstanceLimit limit ) {
    return new LicensePayload( UUID.randomUUID(), "Acme Corp", "ee", 1_700_000_000_000L, 0, limit );
  }

  @Test
  public void aLicenseWithoutALimitIsStillVersion1() {
    byte[] bytes = payload( InstanceLimit.NONE ).toBytes();
    assertEquals( 1, bytes[ 0 ] );
    assertEquals( 1 + 16 + 2 + "Acme Corp".length() + 1 + 2 + 8 + 8, bytes.length );
  }

  @Test
  public void aLicenseWithALimitIsVersion2AndRoundTrips() throws Exception {
    for ( InstanceLimit limit : new InstanceLimit[] { FOLDER_300,
      InstanceLimit.of( 5, InstanceLimit.Counting.DECLARED, false ),
      InstanceLimit.of( 1, InstanceLimit.Counting.FOLDER, true ) } ) {
      LicensePayload p = payload( limit );
      byte[] bytes = p.toBytes();
      assertEquals( 2, bytes[ 0 ] );
      LicensePayload back = LicensePayload.fromBytes( bytes );
      assertEquals( p, back );
      assertEquals( limit, back.getInstanceLimit() );
    }
  }

  /** Codes issued before limits existed, committed to this repository: they read, verify and re-encode unchanged. */
  @Test
  public void codesIssuedBeforeLimitsStillVerify() throws Exception {
    String activation = new String( Files.readAllBytes( Paths.get(
      "src/test/resources/org/pentaho/di/license/dev-unbound.helixact" ) ), StandardCharsets.UTF_8 ).trim();
    Activation a = Activation.decode( activation );
    assertFalse( a.getLicense().getInstanceLimit().isLimited() );
    assertEquals( activation.replaceAll( "\\s+", "" ), a.encode() );
    assertTrue( new LicenseVerifier().verify( activation ).isValid() );

    String key = new String( Files.readAllBytes( Paths.get( "../qa/cve-remediation/license/dev-entitlement.key" ) ),
      StandardCharsets.UTF_8 ).trim();
    assertEquals( key.replaceAll( "\\s+", "" ), LicenseKey.decode( key ).encode() );
    assertEquals( LicenseValidationResult.Status.VALID, new LicenseVerifier().verifyEntitlement( key ).getStatus() );
  }

  /** The limit is signed: raising it breaks the signature. */
  @Test
  public void changingTheLimitBreaksTheSignature() throws Exception {
    LicenseKey key = LicenseKey.sign( payload( FOLDER_300 ), TestKeys.devPrivateKey() );
    assertTrue( new LicenseVerifier().verifyEntitlement( key ).isValid() );
    byte[] raw = key.toRaw();
    raw[ maxOffset( raw ) + 2 ] = 0x10;   // 300 = 0x0000012C -> 0x0000102C
    LicenseKey tampered = LicenseKey.fromRaw( raw );
    assertEquals( 4140, tampered.getPayload().getInstanceLimit().getMax() );
    assertEquals( LicenseValidationResult.Status.INVALID_SIGNATURE,
      new LicenseVerifier().verifyEntitlement( tampered ).getStatus() );
  }

  @Test
  public void aCountingMethodThisPdiDoesntKnowNeedsANewerPdi() throws Exception {
    byte[] raw = LicenseKey.sign( payload( FOLDER_300 ), TestKeys.devPrivateKey() ).toRaw();
    raw[ maxOffset( raw ) + 4 ] = 9;
    try {
      LicenseKey.fromRaw( raw );
      fail( "expected a refusal" );
    } catch ( LicenseException e ) {
      assertTrue( e.getMessage(), e.getMessage().contains( "needs a newer PDI" ) );
    }
  }

  @Test
  public void aVersion2PayloadWithoutALimitIsMalformed() throws Exception {
    byte[] raw = LicenseKey.sign( payload( FOLDER_300 ), TestKeys.devPrivateKey() ).toRaw();
    int at = maxOffset( raw );
    raw[ at + 2 ] = 0;
    raw[ at + 3 ] = 0;
    try {
      LicenseKey.fromRaw( raw );
      fail( "expected a refusal" );
    } catch ( LicenseException e ) {
      assertTrue( e.getMessage(), e.getMessage().contains( "Malformed" ) );
    }
  }

  @Test
  public void aLimitIsAtLeastOne() {
    for ( int max : new int[] { 0, -1 } ) {
      try {
        InstanceLimit.of( max, InstanceLimit.Counting.FOLDER, false );
        fail( "expected a refusal of " + max );
      } catch ( IllegalArgumentException e ) {
        // expected
      }
    }
  }

  @Test
  public void describesItself() {
    assertEquals( "up to 300 at once, counted in a shared folder (over the limit: refused)", FOLDER_300.describe() );
    assertEquals( "up to 2 at once, declared, not counted",
      InstanceLimit.of( 2, InstanceLimit.Counting.DECLARED, true ).describe() );
    assertEquals( "no limit", InstanceLimit.NONE.describe() );
    assertEquals( InstanceLimit.Counting.FOLDER, InstanceLimit.Counting.parse( " Folder " ) );
  }

  /** Where the 4-byte maximum starts in a key's raw form: the payload's last 6 bytes are max, counting, flags. */
  private static int maxOffset( byte[] raw ) {
    int payloadLength = ( ( raw[ 1 ] & 0xFF ) << 8 ) | ( raw[ 2 ] & 0xFF );
    return 3 + payloadLength - 6;
  }
}
