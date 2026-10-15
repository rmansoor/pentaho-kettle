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

import java.security.PrivateKey;
import java.security.PublicKey;

/**
 * An entitlement key: a {@link LicensePayload} plus the signature over it, as sold to a customer. On its own it
 * doesn't license a machine: it is what the customer activates ({@link ActivationRequest}), and the
 * {@link Activation} issued for that request is what PDI checks at startup.
 * <p>
 * {@link #decode(String)} only parses the wire format; trust comes from {@link LicenseVerifier}, which checks the
 * signature against the embedded public key and the expiry.
 * <p>
 * Wire format (before encoding): {@code [1-byte key format version][2-byte payload length][payload
 * bytes][2-byte signature length][signature bytes]}, then the whole thing is base64url-encoded (no
 * padding) with a short human-readable prefix so a key is recognizable at a glance.
 */
public final class LicenseKey {

  /** Bump when the key envelope format changes (independent of {@link LicensePayload#FORMAT_VERSION}). */
  static final byte KEY_FORMAT_VERSION = 1;

  /** Purely cosmetic: lets a human (or a quick regex) recognize an entitlement key at a glance. */
  public static final String PREFIX = "HELIX1-";

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
    return new LicenseKey( payload, Codec.sign( payload.toBytes(), privateKey ) );
  }

  /** Encodes this key (payload + signature) to the distributable key string. */
  public String encode() {
    return Codec.toText( PREFIX, toRaw() );
  }

  byte[] toRaw() {
    return Codec.envelope( KEY_FORMAT_VERSION, payload.toBytes(), signature );
  }

  /** Parses a key string into its payload and signature. Does not verify the signature. */
  public static LicenseKey decode( String keyString ) throws LicenseException {
    if ( keyString == null ) {
      throw new LicenseException( "License key is missing" );
    }
    return fromRaw( Codec.fromText( PREFIX, false, keyString, "license key" ) );
  }

  static LicenseKey fromRaw( byte[] raw ) throws LicenseException {
    byte[][] parts = Codec.openEnvelope( KEY_FORMAT_VERSION, raw, "license key" );
    return new LicenseKey( LicensePayload.fromBytes( parts[ 0 ] ), parts[ 1 ] );
  }

  /** Verifies the signature over the payload against {@code publicKey}. Does not check expiry. */
  boolean hasValidSignature( PublicKey publicKey ) throws LicenseException {
    return Codec.verify( payload.toBytes(), signature, publicKey );
  }

  @Override
  public String toString() {
    // deliberately does not include the signature or the raw encoded key
    return "LicenseKey{" + payload + "}";
  }
}
