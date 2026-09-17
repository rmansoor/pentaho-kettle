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

package org.pentaho.di.license.tool;

import org.pentaho.di.license.LicenseException;
import org.pentaho.di.license.LicenseKey;
import org.pentaho.di.license.LicensePayload;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * INTERNAL TOOL — issues signed license keys. Run offline, by Hitachi Vantara, at sale time, holding
 * the real private signing key. This class is <b>not</b> meant to ship in the customer-facing client
 * assembly (the private key never should either); it lives in this module only because it shares the
 * wire format with {@link org.pentaho.di.license.LicenseVerifier}, and reusing that guarantees the two
 * never drift apart.
 * <p>
 * Usage:
 * <pre>
 *   license-issuer --private-key /path/to/private-key.der \
 *                   --customer "Acme Corp" \
 *                   --edition ce \
 *                   [--expires-in-days 365 | --perpetual]
 * </pre>
 */
public final class LicenseIssuerTool {

  private LicenseIssuerTool() {
  }

  public static void main( String[] args ) throws Exception {
    Map<String, String> options = parseOptions( args );

    requireOption( options, "private-key" );
    requireOption( options, "customer" );
    String edition = options.getOrDefault( "edition", "ce" );

    PrivateKey privateKey = readPrivateKey( Paths.get( options.get( "private-key" ) ) );

    long issuedAt = System.currentTimeMillis();
    long expiresAt = 0; // perpetual by default
    if ( options.containsKey( "expires-in-days" ) ) {
      int days = Integer.parseInt( options.get( "expires-in-days" ) );
      expiresAt = Instant.ofEpochMilli( issuedAt ).plus( Duration.ofDays( days ) ).toEpochMilli();
    }

    LicensePayload payload = new LicensePayload(
      UUID.randomUUID(), options.get( "customer" ), edition, issuedAt, expiresAt );
    LicenseKey key = LicenseKey.sign( payload, privateKey );

    System.out.println( key.encode() );
  }

  private static PrivateKey readPrivateKey( Path path ) throws LicenseException {
    byte[] der;
    try {
      der = Files.readAllBytes( path );
    } catch ( IOException e ) {
      throw new LicenseException( "Could not read private key file: " + path, e );
    }
    try {
      KeyFactory keyFactory = KeyFactory.getInstance( "EC" );
      return keyFactory.generatePrivate( new PKCS8EncodedKeySpec( der ) );
    } catch ( NoSuchAlgorithmException | InvalidKeySpecException e ) {
      throw new LicenseException( "Could not load private key (expected PKCS8 DER, EC): " + path, e );
    }
  }

  private static Map<String, String> parseOptions( String[] args ) {
    Map<String, String> options = new HashMap<>();
    for ( int i = 0; i < args.length; i++ ) {
      String arg = args[ i ];
      if ( !arg.startsWith( "--" ) ) {
        continue;
      }
      String name = arg.substring( 2 );
      if ( "perpetual".equals( name ) ) {
        options.put( name, "true" );
      } else if ( i + 1 < args.length ) {
        options.put( name, args[ ++i ] );
      }
    }
    return options;
  }

  private static void requireOption( Map<String, String> options, String name ) {
    if ( !options.containsKey( name ) ) {
      throw new IllegalArgumentException( "missing required option: --" + name );
    }
  }
}
