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
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Verifies licenses entirely offline: signature against the embedded public key, expiry, and for an
 * {@link Activation}, that this machine is the activated one. No network access, no server lookup.
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
  private final Function<UUID, MachineFingerprint> thisMachine;
  private final LongSupplier clock;

  public LicenseVerifier() throws LicenseException {
    this( loadEmbeddedPublicKey() );
  }

  /** Verify against another public key (tests, or an issuer checking keys it signed with a different key). */
  public LicenseVerifier( PublicKey publicKey ) {
    this( publicKey, MachineFingerprint::ofThisMachine, System::currentTimeMillis );
  }

  /** For tests: a given machine and clock. */
  LicenseVerifier( PublicKey publicKey, Function<UUID, MachineFingerprint> thisMachine, LongSupplier clock ) {
    this.publicKey = publicKey;
    this.thisMachine = thisMachine;
    this.clock = clock;
  }

  /**
   * Verifies what is installed on this machine: an activation (valid if genuine, unexpired and for this machine)
   * or an entitlement key (never valid on its own: NOT_ACTIVATED if genuine).
   */
  public LicenseValidationResult verify( String installed ) {
    if ( installed == null ) {
      return LicenseValidationResult.malformed( "License key is missing" );
    }
    if ( installed.trim().startsWith( Activation.PREFIX ) ) {
      try {
        return verify( Activation.decode( installed ) );
      } catch ( LicenseException e ) {
        return LicenseValidationResult.malformed( e.getMessage() );
      }
    }
    LicenseValidationResult entitlement = verifyEntitlement( installed );
    return entitlement.isValid() ? LicenseValidationResult.notActivated( entitlement.getPayload() ) : entitlement;
  }

  public LicenseValidationResult verify( Activation activation ) {
    try {
      if ( !activation.hasValidSignature( publicKey ) ) {
        return LicenseValidationResult.invalidSignature();
      }
    } catch ( LicenseException e ) {
      return LicenseValidationResult.malformed( e.getMessage() );
    }
    LicensePayload license = activation.getLicense();
    if ( isExpired( license ) ) {
      return LicenseValidationResult.expired( license );
    }
    if ( activation.isBound() ) {
      String why = activation.getFingerprint().mismatch( thisMachine.apply( license.getLicenseId() ) );
      if ( why != null ) {
        return LicenseValidationResult.wrongMachine( activation, why );
      }
    }
    return LicenseValidationResult.valid( activation );
  }

  /** Verifies an entitlement key: genuine and unexpired. Says nothing about activation. */
  public LicenseValidationResult verifyEntitlement( String keyString ) {
    try {
      return verifyEntitlement( LicenseKey.decode( keyString ) );
    } catch ( LicenseException e ) {
      return LicenseValidationResult.malformed( e.getMessage() );
    }
  }

  public LicenseValidationResult verifyEntitlement( LicenseKey key ) {
    try {
      if ( !key.hasValidSignature( publicKey ) ) {
        return LicenseValidationResult.invalidSignature();
      }
    } catch ( LicenseException e ) {
      return LicenseValidationResult.malformed( e.getMessage() );
    }
    LicensePayload payload = key.getPayload();
    return isExpired( payload ) ? LicenseValidationResult.expired( payload ) : LicenseValidationResult.valid( payload );
  }

  private boolean isExpired( LicensePayload payload ) {
    return !payload.isPerpetual() && payload.getExpiresAt() < clock.getAsLong();
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
    return publicKey( der );
  }

  /** An EC public key from its X.509 DER encoding. */
  public static PublicKey publicKey( byte[] der ) throws LicenseException {
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
