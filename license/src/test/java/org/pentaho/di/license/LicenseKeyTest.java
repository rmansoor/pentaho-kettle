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

public class LicenseKeyTest {

  @Test
  public void encodeDecodeRoundTripsAndHasTheExpectedPrefix() throws Exception {
    LicensePayload payload = new LicensePayload( UUID.randomUUID(), "Acme Corp", "ce", 0, 0 );
    LicenseKey signed = LicenseKey.sign( payload, TestKeys.devPrivateKey() );

    String encoded = signed.encode();
    assertTrue( encoded.startsWith( LicenseKey.PREFIX ) );

    LicenseKey decoded = LicenseKey.decode( encoded );
    assertEquals( payload, decoded.getPayload() );
  }

  @Test
  public void decodeToleratesMissingPrefix() throws Exception {
    LicensePayload payload = new LicensePayload( UUID.randomUUID(), "Acme Corp", "ce", 0, 0 );
    String encoded = LicenseKey.sign( payload, TestKeys.devPrivateKey() ).encode();
    String withoutPrefix = encoded.substring( LicenseKey.PREFIX.length() );

    LicenseKey decoded = LicenseKey.decode( withoutPrefix );

    assertEquals( payload, decoded.getPayload() );
  }

  @Test
  public void signatureVerifiesOnlyAgainstTheMatchingPublicKey() throws Exception {
    LicensePayload payload = new LicensePayload( UUID.randomUUID(), "Acme Corp", "ce", 0, 0 );
    LicenseKey key = LicenseKey.sign( payload, TestKeys.devPrivateKey() );

    assertTrue( key.hasValidSignature( TestKeys.devPublicKey() ) );
    assertFalse( key.hasValidSignature( TestKeys.unrelatedPublicKey() ) );
  }

  @Test( expected = LicenseException.class )
  public void decodeRejectsGarbageInput() throws Exception {
    LicenseKey.decode( "not a real license key at all!!" );
  }

  @Test( expected = LicenseException.class )
  public void decodeRejectsNull() throws Exception {
    LicenseKey.decode( null );
  }

  @Test
  public void tamperingWithTheEncodedKeyBreaksTheSignature() throws Exception {
    LicensePayload payload = new LicensePayload( UUID.randomUUID(), "Acme Corp", "ee", 0, 0 );
    String encoded = LicenseKey.sign( payload, TestKeys.devPrivateKey() ).encode();

    // flip a character deep in the body, past the prefix, to simulate a customer editing "ee" -> "ce"
    char[] chars = encoded.toCharArray();
    int i = chars.length - 5;
    chars[ i ] = chars[ i ] == 'A' ? 'B' : 'A';
    String tampered = new String( chars );

    LicenseKey decoded = LicenseKey.decode( tampered );
    assertFalse( decoded.hasValidSignature( TestKeys.devPublicKey() ) );
  }
}
