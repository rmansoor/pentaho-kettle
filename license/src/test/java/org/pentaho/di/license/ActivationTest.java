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

import java.util.Collections;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** The request -> activation -> release round trip, without the issuer's ledger. */
public class ActivationTest {

  private static final LicensePayload LICENSE =
    new LicensePayload( UUID.randomUUID(), "Acme Corp", "ce", 0, 0 );

  private static MachineFingerprint machine( String id ) {
    return MachineFingerprint.of( LICENSE.getLicenseId(),
      new MachineInfo( "linux", id, "host-" + id, Collections.singletonList( "00112233445" + id.charAt( 0 ) ) ) );
  }

  private static LicenseVerifier verifierOn( MachineFingerprint here ) throws Exception {
    return new LicenseVerifier( TestKeys.devPublicKey(), licenseId -> here, System::currentTimeMillis );
  }

  @Test
  public void requestRoundTrips() throws Exception {
    LicenseKey key = LicenseKey.sign( LICENSE, TestKeys.devPrivateKey() );
    ActivationRequest request = new ActivationRequest( key, machine( "1" ), 1234L );
    ActivationRequest back = ActivationRequest.decode( request.encode() );
    assertEquals( LICENSE, back.getEntitlement().getPayload() );
    assertEquals( machine( "1" ), back.getFingerprint() );
    assertEquals( 1234L, back.getRequestedAt() );
  }

  @Test
  public void aRequestWrappedByAMailClientStillDecodes() throws Exception {
    String code = new ActivationRequest( LicenseKey.sign( LICENSE, TestKeys.devPrivateKey() ), machine( "1" ), 0 )
      .encode();
    String wrapped = code.substring( 0, 40 ) + "\n  " + code.substring( 40, 90 ) + "\r\n" + code.substring( 90 );
    assertEquals( machine( "1" ), ActivationRequest.decode( wrapped ).getFingerprint() );
  }

  @Test
  public void aDamagedRequestIsReportedAsDamaged() throws Exception {
    String code = new ActivationRequest( LicenseKey.sign( LICENSE, TestKeys.devPrivateKey() ), machine( "1" ), 0 )
      .encode();
    int i = code.length() / 2;
    String damaged = code.substring( 0, i ) + ( code.charAt( i ) == 'A' ? 'B' : 'A' ) + code.substring( i + 1 );
    try {
      ActivationRequest.decode( damaged );
      fail( "expected a LicenseException" );
    } catch ( LicenseException e ) {
      assertTrue( e.getMessage(), e.getMessage().contains( "damaged" ) );
    }
  }

  @Test
  public void anActivationIsValidOnItsMachineOnly() throws Exception {
    Activation activation = Activation.sign( LICENSE, UUID.randomUUID(), 5L, machine( "1" ), TestKeys.devPrivateKey() );
    String code = activation.encode();
    assertTrue( verifierOn( machine( "1" ) ).verify( code ).isValid() );
    LicenseValidationResult elsewhere = verifierOn( machine( "2" ) ).verify( code );
    assertEquals( LicenseValidationResult.Status.WRONG_MACHINE, elsewhere.getStatus() );
    assertEquals( LICENSE, elsewhere.getPayload() );
  }

  @Test
  public void anActivationRoundTrips() throws Exception {
    UUID id = UUID.randomUUID();
    Activation back = Activation.decode(
      Activation.sign( LICENSE, id, 5L, machine( "1" ), TestKeys.devPrivateKey() ).encode() );
    assertEquals( LICENSE, back.getLicense() );
    assertEquals( id, back.getActivationId() );
    assertEquals( 5L, back.getActivatedAt() );
    assertEquals( machine( "1" ), back.getFingerprint() );
  }

  @Test
  public void anUnboundActivationIsValidAnywhere() throws Exception {
    String code = Activation.sign( LICENSE, UUID.randomUUID(), 5L, null, TestKeys.devPrivateKey() ).encode();
    assertTrue( verifierOn( machine( "1" ) ).verify( code ).isValid() );
    assertTrue( verifierOn( machine( "2" ) ).verify( code ).isValid() );
    assertFalse( Activation.decode( code ).isBound() );
  }

  @Test
  public void anActivationSignedByAnyoneElseIsRejected() throws Exception {
    String code = Activation.sign( LICENSE, UUID.randomUUID(), 5L, machine( "1" ),
      TestKeys.unrelatedPrivateKeyForSigning() ).encode();
    assertEquals( LicenseValidationResult.Status.INVALID_SIGNATURE,
      verifierOn( machine( "1" ) ).verify( code ).getStatus() );
  }

  @Test
  public void rebindingAnActivationToAnotherMachineBreaksTheSignature() throws Exception {
    // what a customer would try: keep the signature, swap in this machine's fingerprint
    Activation genuine = Activation.sign( LICENSE, UUID.randomUUID(), 5L, machine( "1" ), TestKeys.devPrivateKey() );
    Activation forged = Activation.sign( LICENSE, genuine.getActivationId(), 5L, machine( "2" ),
      TestKeys.unrelatedPrivateKeyForSigning() );
    String forgedPayloadGenuineSignature = spliceSignature( forged.encode(), genuine.encode() );
    LicenseValidationResult result = verifierOn( machine( "2" ) ).verify( forgedPayloadGenuineSignature );
    assertFalse( result.isValid() );
  }

  @Test
  public void anExpiredActivationIsRejected() throws Exception {
    LicensePayload expired = new LicensePayload( UUID.randomUUID(), "Acme Corp", "ce", 0, 1000L );
    String code = Activation.sign( expired, UUID.randomUUID(), 5L, null, TestKeys.devPrivateKey() ).encode();
    assertEquals( LicenseValidationResult.Status.EXPIRED, verifierOn( machine( "1" ) ).verify( code ).getStatus() );
  }

  @Test
  public void releaseCodeRoundTrips() throws Exception {
    UUID activation = UUID.randomUUID();
    ReleaseCode back = ReleaseCode.decode( new ReleaseCode( LICENSE.getLicenseId(), activation, 99L ).encode() );
    assertEquals( LICENSE.getLicenseId(), back.getLicenseId() );
    assertEquals( activation, back.getActivationId() );
    assertEquals( 99L, back.getReleasedAt() );
  }

  @Test
  public void codesAreNotMistakenForEachOther() throws Exception {
    String release = new ReleaseCode( LICENSE.getLicenseId(), UUID.randomUUID(), 0 ).encode();
    try {
      ActivationRequest.decode( release );
      fail( "expected a LicenseException" );
    } catch ( LicenseException e ) {
      assertTrue( e.getMessage(), e.getMessage().contains( ActivationRequest.PREFIX ) );
    }
  }

  /** {@code payloadFrom}'s payload with {@code signatureFrom}'s signature. */
  private static String spliceSignature( String payloadFrom, String signatureFrom ) throws Exception {
    byte[][] a = Codec.openEnvelope( Activation.ENVELOPE_VERSION,
      Codec.fromText( Activation.PREFIX, true, payloadFrom, "x" ), "x" );
    byte[][] b = Codec.openEnvelope( Activation.ENVELOPE_VERSION,
      Codec.fromText( Activation.PREFIX, true, signatureFrom, "x" ), "x" );
    return Codec.toText( Activation.PREFIX, Codec.envelope( Activation.ENVELOPE_VERSION, a[ 0 ], b[ 1 ] ) );
  }
}
