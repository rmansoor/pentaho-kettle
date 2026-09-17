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

import java.io.InputStream;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

/**
 * Test helper: loads the dev keypair embedded/bundled for this repo (public key in main resources,
 * matching private key in test resources), so tests exercise the real shipped verification path
 * end to end rather than a synthetic keypair.
 */
final class TestKeys {

  private static final String DEV_PRIVATE_KEY_RESOURCE = "/org/pentaho/di/license/dev-private-key.der";
  private static final String DEV_PUBLIC_KEY_RESOURCE = "/org/pentaho/di/license/helix-license-public-key.der";

  private TestKeys() {
  }

  /** The private key matching {@link #devPublicKey()} / the key embedded in {@link LicenseVerifier}. */
  static PrivateKey devPrivateKey() throws Exception {
    KeyFactory keyFactory = KeyFactory.getInstance( "EC" );
    return keyFactory.generatePrivate( new PKCS8EncodedKeySpec( readResource( DEV_PRIVATE_KEY_RESOURCE ) ) );
  }

  /** Same public key {@link LicenseVerifier} embeds by default. */
  static PublicKey devPublicKey() throws Exception {
    KeyFactory keyFactory = KeyFactory.getInstance( "EC" );
    return keyFactory.generatePublic( new X509EncodedKeySpec( readResource( DEV_PUBLIC_KEY_RESOURCE ) ) );
  }

  /** A fresh, unrelated EC public key — for negative "wrong signer" tests. */
  static PublicKey unrelatedPublicKey() throws NoSuchAlgorithmException {
    return generateUnrelatedKeyPair().getPublic();
  }

  /** The private half of a fresh, unrelated EC keypair — simulates a key not signed by us at all. */
  static PrivateKey unrelatedPrivateKeyForSigning() throws NoSuchAlgorithmException {
    return generateUnrelatedKeyPair().getPrivate();
  }

  private static java.security.KeyPair generateUnrelatedKeyPair() throws NoSuchAlgorithmException {
    KeyPairGenerator generator = KeyPairGenerator.getInstance( "EC" );
    generator.initialize( 256 );
    return generator.generateKeyPair();
  }

  private static byte[] readResource( String resource ) throws Exception {
    try ( InputStream in = TestKeys.class.getResourceAsStream( resource ) ) {
      if ( in == null ) {
        throw new IllegalStateException( "test resource missing: " + resource );
      }
      java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
      byte[] chunk = new byte[ 4096 ];
      int n;
      while ( ( n = in.read( chunk ) ) != -1 ) {
        buffer.write( chunk, 0, n );
      }
      return buffer.toByteArray();
    }
  }
}
