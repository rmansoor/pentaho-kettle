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

package org.pentaho.di.license.cli;

import org.pentaho.di.license.LicenseEnforcement;
import org.pentaho.di.license.LicenseException;
import org.pentaho.di.license.LicensePayload;
import org.pentaho.di.license.LicenseStore;
import org.pentaho.di.license.LicenseValidationResult;
import org.pentaho.di.license.LicenseVerifier;

import java.time.Instant;
import java.util.Optional;

/**
 * Customer-facing license management: {@code install}, {@code remove}, {@code status}. Intended to be
 * wrapped by a {@code license.sh}/{@code license.bat} launcher script alongside {@code spoon.sh},
 * {@code carte.sh}, etc. in the client assembly.
 * <p>
 * Not the same tool as {@link org.pentaho.di.license.tool.LicenseIssuerTool} (internal, offline,
 * signs new keys) &mdash; this tool only ever reads/writes/verifies a key it's given, it never signs one.
 */
public class LicenseCli {

  public static void main( String[] args ) {
    int exitCode = run( args );
    if ( exitCode != 0 ) {
      System.exit( exitCode );
    }
  }

  static int run( String[] args ) {
    if ( args.length == 0 ) {
      printUsage();
      return 2;
    }

    try {
      switch ( args[ 0 ] ) {
        case "install":
          return install( args );
        case "remove":
          return remove();
        case "status":
          return status();
        default:
          printUsage();
          return 2;
      }
    } catch ( LicenseException e ) {
      System.err.println( "error: " + e.getMessage() );
      return 1;
    }
  }

  private static int install( String[] args ) throws LicenseException {
    if ( args.length < 2 ) {
      System.err.println( "usage: license install <key>" );
      return 2;
    }
    String keyString = args[ 1 ];

    LicenseValidationResult result = new LicenseVerifier().verify( keyString );
    if ( !result.isValid() ) {
      System.err.println( "Not installing: " + result.getMessage() );
      return 1;
    }

    LicenseStore.write( keyString );
    System.out.println( "License installed: " + describe( result ) );
    return 0;
  }

  private static int remove() throws LicenseException {
    LicenseStore.remove();
    System.out.println( "License removed." );
    return 0;
  }

  private static int status() throws LicenseException {
    Optional<String> installed = LicenseStore.read();
    if ( !installed.isPresent() ) {
      System.out.println( "No license installed. (" + LicenseStore.getLicenseFilePath() + ")" );
      return 1;
    }

    LicenseValidationResult result = new LicenseVerifier().verify( installed.get() );
    if ( result.isValid() ) {
      System.out.println( "Licensed: " + describe( result ) );
      return 0;
    }
    System.out.println( "Not licensed: " + result.getMessage() );
    return 1;
  }

  private static String describe( LicenseValidationResult result ) {
    LicensePayload payload = result.getPayload();
    if ( payload == null ) {
      return result.getMessage();
    }
    String expiry = payload.isPerpetual() ? "perpetual" : Instant.ofEpochMilli( payload.getExpiresAt() ).toString();
    return "customer=" + payload.getCustomerId() + ", edition=" + payload.getEdition() + ", expires=" + expiry;
  }

  private static void printUsage() {
    System.out.println( "usage: license <install <key> | remove | status>" );
    System.out.println();
    System.out.println( "  install <key>   verify and install a license key" );
    System.out.println( "  remove          remove the installed license key" );
    System.out.println( "  status          show whether a valid license is installed, and its details" );
    System.out.println();
    System.out.println( "License file: " + LicenseStore.getLicenseFilePath() );
    System.out.println( "Env override: -D" + LicenseEnforcement.LICENSE_KEY_SYSTEM_PROPERTY + "=<key>" );
  }
}
