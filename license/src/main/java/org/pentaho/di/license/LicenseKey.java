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

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;

/**
 * A license key: a {@link LicensePayload} plus the signature over it. {@link #decode(String)} only
 * parses the wire format &mdash; it does not, by itself, mean the key is trustworthy. Trust comes from
 * {@link LicenseVerifier#verify(LicenseKey)}, which checks the signature against the embedded public
 * key and the expiry.
 * <p>
 * Wire format (before encoding): {@code [1-byte key format version][2-byte payload length][payload
 * bytes][2-byte signature length][signature bytes]}, then the whole thing is base64url-encoded (no
 * padding) with a short human-readable prefix so a key is recognizable at a glance and typos are
 * cheap to catch before attempting a full decode.
 */
public final class LicenseKey {

  /** Bump when the key envelope format changes (independent of {@link LicensePayload#FORMAT_VERSION}). */
  static final byte KEY_FORMAT_VERSION = 1;

  /** Purely cosmetic: lets a human (or a quick regex) recognize a Helix license key at a glance. */
  public static final String PREFIX = "HELIX1-";

  private static final String SIGNATURE_ALGORITHM = "SHA256withECDSA";

  private final LicensePayload payload;
  private final byte[] signature;

  private LicenseKey( LicensePayload payload, byte[] signature ) {
    this.payload = payload;
    this.signature = signature;
  }

  public LicensePayload getPayload() {
    return payload;
  }

  byte[] getSignature() {
    return signature.clone();
  }

  /**
   * Signs {@code payload} with {@code privateKey} and returns the resulting key. Used only by the
   * (internal, offline) license-issuing tool &mdash; never by the client.
   */
  public static LicenseKey sign( LicensePayload payload, PrivateKey privateKey ) throws LicenseException {
    byte[] payloadBytes = payload.toBytes();
    try {
      Signature signer = Signature.getInstance( SIGNATURE_ALGORITHM );
      signer.initSign( privateKey );
      signer.update( payloadBytes );
      byte[] signature = signer.sign();
      return new LicenseKey( payload, signature );
    } catch ( GeneralSecurityException e ) {
      throw new LicenseException( "Failed to sign license payload", e );
    }
  }

  /** Encodes this key (payload + signature) to the distributable key string. */
  public String encode() {
    byte[] payloadBytes = payload.toBytes();
    if ( payloadBytes.length > 0xFFFF || signature.length > 0xFFFF ) {
      // cannot happen with today's payload/signature sizes; guards against a future format mistake
      throw new IllegalStateException( "payload or signature too large to encode" );
    }

    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try ( DataOutputStream out = new DataOutputStream( buffer ) ) {
      out.writeByte( KEY_FORMAT_VERSION );
      out.writeShort( payloadBytes.length );
      out.write( payloadBytes );
      out.writeShort( signature.length );
      out.write( signature );
    } catch ( IOException e ) {
      throw new UncheckedIOException( e );
    }

    return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString( buffer.toByteArray() );
  }

  /** Parses a key string into its payload and signature. Does not verify the signature. */
  public static LicenseKey decode( String keyString ) throws LicenseException {
    if ( keyString == null ) {
      throw new LicenseException( "License key is missing" );
    }
    String trimmed = keyString.trim();
    String body = trimmed.startsWith( PREFIX ) ? trimmed.substring( PREFIX.length() ) : trimmed;

    byte[] raw;
    try {
      raw = Base64.getUrlDecoder().decode( body );
    } catch ( IllegalArgumentException e ) {
      throw new LicenseException( "License key is not validly encoded", e );
    }

    try ( DataInputStream in = new DataInputStream( new java.io.ByteArrayInputStream( raw ) ) ) {
      int version = in.readUnsignedByte();
      if ( version != KEY_FORMAT_VERSION ) {
        throw new LicenseException( "Unsupported license key format version: " + version );
      }
      int payloadLen = in.readUnsignedShort();
      byte[] payloadBytes = new byte[ payloadLen ];
      in.readFully( payloadBytes );

      int sigLen = in.readUnsignedShort();
      byte[] signature = new byte[ sigLen ];
      in.readFully( signature );

      LicensePayload payload = LicensePayload.fromBytes( payloadBytes );
      return new LicenseKey( payload, signature );
    } catch ( IOException e ) {
      throw new LicenseException( "Malformed license key", e );
    }
  }

  /** Verifies the signature over the payload against {@code publicKey}. Does not check expiry. */
  boolean hasValidSignature( PublicKey publicKey ) throws LicenseException {
    try {
      Signature verifier = Signature.getInstance( SIGNATURE_ALGORITHM );
      verifier.initVerify( publicKey );
      verifier.update( payload.toBytes() );
      return verifier.verify( signature );
    } catch ( GeneralSecurityException e ) {
      throw new LicenseException( "Failed to verify license signature", e );
    }
  }

  @Override
  public String toString() {
    // deliberately does not include the signature or the raw encoded key
    return "LicenseKey{" + payload + "}";
  }
}
