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
import static org.junit.Assert.assertTrue;

public class LicensePayloadTest {

  @Test
  public void roundTripsThroughBytes() throws Exception {
    LicensePayload original = new LicensePayload(
      UUID.randomUUID(), "Acme Corp", "ce", 1_700_000_000_000L, 1_800_000_000_000L );

    LicensePayload roundTripped = LicensePayload.fromBytes( original.toBytes() );

    assertEquals( original, roundTripped );
  }

  @Test
  public void perpetualWhenExpiresAtIsZeroOrNegative() {
    LicensePayload perpetual = new LicensePayload( UUID.randomUUID(), "c", "ce", 0, 0 );
    LicensePayload timeBound = new LicensePayload( UUID.randomUUID(), "c", "ce", 0, System.currentTimeMillis() + 1 );

    assertTrue( perpetual.isPerpetual() );
    assertTrue( !timeBound.isPerpetual() );
  }

  @Test
  public void handlesEmptyCustomerAndEditionStrings() throws Exception {
    LicensePayload payload = new LicensePayload( UUID.randomUUID(), "", "", 0, 0 );

    LicensePayload roundTripped = LicensePayload.fromBytes( payload.toBytes() );

    assertEquals( payload, roundTripped );
  }

  @Test( expected = LicenseException.class )
  public void rejectsAnUnknownFormatVersion() throws Exception {
    byte[] bytes = new LicensePayload( UUID.randomUUID(), "c", "ce", 0, 0 ).toBytes();
    bytes[ 0 ] = (byte) 0xFF; // corrupt the format-version byte
    LicensePayload.fromBytes( bytes );
  }
}
