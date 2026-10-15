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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * What a customer's machine sends to Hitachi Vantara to activate an entitlement key: the key itself and this
 * machine's {@link MachineFingerprint}. Produced offline ({@code license request}), carried by the customer by any
 * means (mail from another computer, a portal, a support ticket), and answered with an {@link Activation}.
 * <p>
 * Unsigned on purpose: the issuer checks the entitlement inside it, and a request naming some other machine's
 * fingerprint still costs the customer their one activation. A CRC catches copy errors.
 */
public final class ActivationRequest {

  public static final String PREFIX = "HELIXREQ-";
  static final byte FORMAT_VERSION = 1;

  private final LicenseKey entitlement;
  private final MachineFingerprint fingerprint;
  private final long requestedAt;

  ActivationRequest( LicenseKey entitlement, MachineFingerprint fingerprint, long requestedAt ) {
    this.entitlement = entitlement;
    this.fingerprint = fingerprint;
    this.requestedAt = requestedAt;
  }

  /** A request to activate {@code entitlement} on this machine. */
  public static ActivationRequest forThisMachine( LicenseKey entitlement ) {
    return new ActivationRequest( entitlement,
      MachineFingerprint.ofThisMachine( entitlement.getPayload().getLicenseId() ), System.currentTimeMillis() );
  }

  public LicenseKey getEntitlement() {
    return entitlement;
  }

  public MachineFingerprint getFingerprint() {
    return fingerprint;
  }

  public long getRequestedAt() {
    return requestedAt;
  }

  public String encode() {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try ( DataOutputStream out = new DataOutputStream( buffer ) ) {
      byte[] key = entitlement.toRaw();
      out.writeByte( FORMAT_VERSION );
      out.writeShort( key.length );
      out.write( key );
      fingerprint.write( out );
      out.writeLong( requestedAt );
    } catch ( IOException e ) {
      throw new UncheckedIOException( e );
    }
    return Codec.toCheckedText( PREFIX, buffer.toByteArray() );
  }

  public static ActivationRequest decode( String text ) throws LicenseException {
    byte[] body = Codec.fromCheckedText( PREFIX, text, "activation request" );
    try ( DataInputStream in = new DataInputStream( new ByteArrayInputStream( body ) ) ) {
      int version = in.readUnsignedByte();
      if ( version != FORMAT_VERSION ) {
        throw new LicenseException( "Unsupported activation request version: " + version );
      }
      byte[] key = new byte[ in.readUnsignedShort() ];
      in.readFully( key );
      LicenseKey entitlement = LicenseKey.fromRaw( key );
      MachineFingerprint fingerprint = MachineFingerprint.read( in );
      return new ActivationRequest( entitlement, fingerprint, in.readLong() );
    } catch ( IOException e ) {
      throw new LicenseException( "Malformed activation request", e );
    }
  }
}
