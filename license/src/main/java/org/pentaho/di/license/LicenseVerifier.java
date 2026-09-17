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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;

/**
 * Verifies a {@link LicenseKey} entirely offline: signature against the embedded public key, then
 * expiry. No network access, no server lookup, no external state beyond the key string itself.
 * <p>
 * The embedded key here ({@code helix-license-public-key.der} on the classpath) is a development-only
 * placeholder generated for this repository (see {@code docs/licensing/README.md}). It must be
 * replaced with the real Hitachi Vantara signing key's public half before any customer-facing build.
 */
public final class LicenseVerifier {

  private static final String PUBLIC_KEY_RESOURCE = "/org/pentaho/di/license/helix-license-public-key.der";
  private static final String KEY_ALGORITHM = "EC";

  private static volatile PublicKey embeddedPublicKey;

  private final PublicKey publicKey;

  public LicenseVerifier() throws LicenseException {
    this( loadEmbeddedPublicKey() );
  }

  /** For tests: verify against an arbitrary key rather than the embedded one. */
  LicenseVerifier( PublicKey publicKey ) {
    this.publicKey = publicKey;
  }

  public LicenseValidationResult verify( String keyString ) {
    LicenseKey key;
    try {
      key = LicenseKey.decode( keyString );
    } catch ( LicenseException e ) {
      return LicenseValidationResult.malformed( e.getMessage() );
    }
    return verify( key );
  }

  public LicenseValidationResult verify( LicenseKey key ) {
    boolean signatureOk;
    try {
      signatureOk = key.hasValidSignature( publicKey );
    } catch ( LicenseException e ) {
      return LicenseValidationResult.malformed( e.getMessage() );
    }
    if ( !signatureOk ) {
      return LicenseValidationResult.invalidSignature();
    }

    LicensePayload payload = key.getPayload();
    if ( !payload.isPerpetual() && payload.getExpiresAt() < System.currentTimeMillis() ) {
      return LicenseValidationResult.expired( payload );
    }

    return LicenseValidationResult.valid( payload );
  }

  private static PublicKey loadEmbeddedPublicKey() throws LicenseException {
    PublicKey cached = embeddedPublicKey;
    if ( cached != null ) {
      return cached;
    }
    synchronized ( LicenseVerifier.class ) {
      if ( embeddedPublicKey == null ) {
        embeddedPublicKey = readPublicKey( PUBLIC_KEY_RESOURCE );
      }
      return embeddedPublicKey;
    }
  }

  static PublicKey readPublicKey( String classpathResource ) throws LicenseException {
    byte[] der;
    try ( InputStream in = LicenseVerifier.class.getResourceAsStream( classpathResource ) ) {
      if ( in == null ) {
        throw new LicenseException( "License public key resource not found: " + classpathResource );
      }
      der = readAllBytes( in );
    } catch ( IOException e ) {
      throw new UncheckedIOException( e );
    }
    try {
      KeyFactory keyFactory = KeyFactory.getInstance( KEY_ALGORITHM );
      return keyFactory.generatePublic( new X509EncodedKeySpec( der ) );
    } catch ( NoSuchAlgorithmException | InvalidKeySpecException e ) {
      throw new LicenseException( "Could not load license public key", e );
    }
  }

  private static byte[] readAllBytes( InputStream in ) throws IOException {
    java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
    byte[] chunk = new byte[ 4096 ];
    int n;
    while ( ( n = in.read( chunk ) ) != -1 ) {
      buffer.write( chunk, 0, n );
    }
    return buffer.toByteArray();
  }
}
