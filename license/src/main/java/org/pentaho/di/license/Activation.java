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
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.UUID;

/**
 * The license PDI checks at startup: the entitlement's {@link LicensePayload}, bound to one machine's
 * {@link MachineFingerprint}, signed by Hitachi Vantara. Issued in answer to an {@link ActivationRequest}; copied
 * to another machine, it doesn't match that machine's fingerprint and PDI refuses to start.
 * <p>
 * An <em>unbound</em> activation (no fingerprint) is for environments without a stable machine identity, such as
 * containers; only the issuer can create one.
 * <p>
 * Signed payload: {@code [1-byte version][u16 length][license payload][16-byte activation id][8-byte activated at]
 * [1-byte bound][fingerprint, if bound]}.
 */
public final class Activation {

  public static final String PREFIX = "HELIXACT-";
  static final byte FORMAT_VERSION = 1;
  static final byte ENVELOPE_VERSION = 1;

  private final LicensePayload license;
  private final UUID activationId;
  private final long activatedAt;
  private final MachineFingerprint fingerprint;   // null: unbound
  private final byte[] signature;

  private Activation( LicensePayload license, UUID activationId, long activatedAt, MachineFingerprint fingerprint,
                      byte[] signature ) {
    this.license = license;
    this.activationId = activationId;
    this.activatedAt = activatedAt;
    this.fingerprint = fingerprint;
    this.signature = signature;
  }

  /**
   * Issues an activation (issuing tool only).
   *
   * @param fingerprint the machine to bind to, or null for an unbound activation
   */
  public static Activation sign( LicensePayload license, UUID activationId, long activatedAt,
                                 MachineFingerprint fingerprint, PrivateKey privateKey ) throws LicenseException {
    Activation unsigned = new Activation( license, activationId, activatedAt, fingerprint, null );
    return new Activation( license, activationId, activatedAt, fingerprint,
      Codec.sign( unsigned.payloadBytes(), privateKey ) );
  }

  public LicensePayload getLicense() {
    return license;
  }

  public UUID getActivationId() {
    return activationId;
  }

  public long getActivatedAt() {
    return activatedAt;
  }

  public boolean isBound() {
    return fingerprint != null;
  }

  /** The activated machine, or null for an unbound activation. */
  public MachineFingerprint getFingerprint() {
    return fingerprint;
  }

  boolean hasValidSignature( PublicKey publicKey ) throws LicenseException {
    return Codec.verify( payloadBytes(), signature, publicKey );
  }

  public String encode() {
    return Codec.toText( PREFIX, Codec.envelope( ENVELOPE_VERSION, payloadBytes(), signature ) );
  }

  public static Activation decode( String text ) throws LicenseException {
    byte[][] parts = Codec.openEnvelope( ENVELOPE_VERSION, Codec.fromText( PREFIX, true, text, "activation code" ),
      "activation code" );
    try ( DataInputStream in = new DataInputStream( new ByteArrayInputStream( parts[ 0 ] ) ) ) {
      int version = in.readUnsignedByte();
      if ( version != FORMAT_VERSION ) {
        throw new LicenseException( "Unsupported activation version: " + version );
      }
      byte[] licenseBytes = new byte[ in.readUnsignedShort() ];
      in.readFully( licenseBytes );
      LicensePayload license = LicensePayload.fromBytes( licenseBytes );
      UUID activationId = new UUID( in.readLong(), in.readLong() );
      long activatedAt = in.readLong();
      MachineFingerprint fingerprint = in.readUnsignedByte() != 0 ? MachineFingerprint.read( in ) : null;
      return new Activation( license, activationId, activatedAt, fingerprint, parts[ 1 ] );
    } catch ( IOException e ) {
      throw new LicenseException( "Malformed activation code", e );
    }
  }

  private byte[] payloadBytes() {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try ( DataOutputStream out = new DataOutputStream( buffer ) ) {
      byte[] licenseBytes = license.toBytes();
      out.writeByte( FORMAT_VERSION );
      out.writeShort( licenseBytes.length );
      out.write( licenseBytes );
      out.writeLong( activationId.getMostSignificantBits() );
      out.writeLong( activationId.getLeastSignificantBits() );
      out.writeLong( activatedAt );
      out.writeByte( fingerprint != null ? 1 : 0 );
      if ( fingerprint != null ) {
        fingerprint.write( out );
      }
    } catch ( IOException e ) {
      throw new UncheckedIOException( e );
    }
    return buffer.toByteArray();
  }

  @Override
  public String toString() {
    return "Activation{" + activationId + ", " + license + ", " + ( isBound() ? fingerprint : "unbound" ) + "}";
  }
}
