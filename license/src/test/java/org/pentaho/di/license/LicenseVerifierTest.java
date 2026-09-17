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

import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Exercises the real, shipped path: {@link LicenseVerifier}'s no-arg constructor loads the embedded
 * {@code helix-license-public-key.der}, and keys here are signed with the matching test-only private
 * key ({@link TestKeys#devPrivateKey()}) — so a pass here means the actual classpath resource wiring
 * works, not just the crypto in isolation.
 */
public class LicenseVerifierTest {

  @Test
  public void aGenuinelySignedPerpetualKeyIsValid() throws Exception {
    LicensePayload payload = new LicensePayload( UUID.randomUUID(), "Acme Corp", "ce", 0, 0 );
    String key = LicenseKey.sign( payload, TestKeys.devPrivateKey() ).encode();

    LicenseValidationResult result = new LicenseVerifier().verify( key );

    assertTrue( result.isValid() );
    assertEquals( LicenseValidationResult.Status.VALID, result.getStatus() );
    assertEquals( payload, result.getPayload() );
  }

  @Test
  public void aGenuinelySignedNotYetExpiredKeyIsValid() throws Exception {
    long farFuture = System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000;
    LicensePayload payload = new LicensePayload( UUID.randomUUID(), "Acme Corp", "ce", 0, farFuture );
    String key = LicenseKey.sign( payload, TestKeys.devPrivateKey() ).encode();

    assertTrue( new LicenseVerifier().verify( key ).isValid() );
  }

  @Test
  public void anExpiredKeyIsRejected() throws Exception {
    long yesterday = System.currentTimeMillis() - 24L * 60 * 60 * 1000;
    LicensePayload payload = new LicensePayload( UUID.randomUUID(), "Acme Corp", "ce", 0, yesterday );
    String key = LicenseKey.sign( payload, TestKeys.devPrivateKey() ).encode();

    LicenseValidationResult result = new LicenseVerifier().verify( key );

    assertFalse( result.isValid() );
    assertEquals( LicenseValidationResult.Status.EXPIRED, result.getStatus() );
    // the payload is still surfaced on an expired result, so the CLI can show "expired on <date>"
    assertEquals( payload, result.getPayload() );
  }

  @Test
  public void aKeyNotSignedByHitachiVantaraIsRejected() throws Exception {
    LicensePayload payload = new LicensePayload( UUID.randomUUID(), "Acme Corp", "ce", 0, 0 );
    String key = LicenseKey.sign( payload, TestKeys.unrelatedPrivateKeyForSigning() ).encode();

    LicenseValidationResult result = new LicenseVerifier().verify( key );

    assertFalse( result.isValid() );
    assertEquals( LicenseValidationResult.Status.INVALID_SIGNATURE, result.getStatus() );
  }

  @Test
  public void garbageStringIsRejectedAsMalformedNotAsAnException() throws Exception {
    LicenseValidationResult result = new LicenseVerifier().verify( "clearly not a license key" );

    assertFalse( result.isValid() );
    assertEquals( LicenseValidationResult.Status.MALFORMED, result.getStatus() );
  }

  @Test
  public void nullStringIsRejectedAsMalformedNotAsAnException() throws Exception {
    LicenseValidationResult result = new LicenseVerifier().verify( (String) null );

    assertFalse( result.isValid() );
    assertEquals( LicenseValidationResult.Status.MALFORMED, result.getStatus() );
  }
}
