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

import org.pentaho.di.license.Activation;
import org.pentaho.di.license.ActivationRequest;
import org.pentaho.di.license.LicenseEnforcement;
import org.pentaho.di.license.LicenseException;
import org.pentaho.di.license.LicenseKey;
import org.pentaho.di.license.LicensePayload;
import org.pentaho.di.license.LicenseStore;
import org.pentaho.di.license.LicenseValidationResult;
import org.pentaho.di.license.LicenseVerifier;
import org.pentaho.di.license.ReleaseCode;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Optional;

/**
 * The customer's license command ({@code license.sh} / {@code License.bat}). Everything runs offline:
 * <pre>
 *   license request &lt;license key&gt; [--out &lt;file&gt;]   print the request code for activating this machine
 *   license install &lt;activation code&gt;              verify the activation is for this machine, and install it
 *   license status                                show whether this machine is licensed
 *   license deactivate                            remove the activation, print the release code
 * </pre>
 * Codes can be given inline or as the path of a file holding them.
 */
public class LicenseCli {

  private final PrintStream out;
  private final PrintStream err;
  private final LicenseVerifier verifier;

  LicenseCli( PrintStream out, PrintStream err, LicenseVerifier verifier ) {
    this.out = out;
    this.err = err;
    this.verifier = verifier;
  }

  public static void main( String[] args ) {
    int exitCode;
    try {
      exitCode = new LicenseCli( System.out, System.err, new LicenseVerifier() ).run( args );
    } catch ( LicenseException e ) {
      System.err.println( "error: " + e.getMessage() );
      exitCode = 1;
    }
    if ( exitCode != 0 ) {
      System.exit( exitCode );
    }
  }

  int run( String[] args ) {
    if ( args.length == 0 ) {
      printUsage();
      return 2;
    }
    try {
      switch ( args[ 0 ] ) {
        case "request":
          return request( args );
        case "install":
          return install( args );
        case "status":
          return status();
        case "deactivate":
          return deactivate();
        default:
          printUsage();
          return 2;
      }
    } catch ( LicenseException e ) {
      err.println( "error: " + e.getMessage() );
      return 1;
    }
  }

  private int request( String[] args ) throws LicenseException {
    if ( args.length != 2 && !( args.length == 4 && args[ 2 ].equals( "--out" ) ) ) {
      err.println( "usage: license request <license key | file> [--out <file>]" );
      return 2;
    }
    LicenseKey key = LicenseKey.decode( textOf( args[ 1 ] ) );
    LicenseValidationResult result = verifier.verifyEntitlement( key );
    if ( !result.isValid() ) {
      err.println( "Not a usable license key: " + result.getMessage() );
      return 1;
    }
    ActivationRequest request = ActivationRequest.forThisMachine( key );
    if ( !request.getFingerprint().isStable() ) {
      err.println( "This machine has no stable identity (" + request.getFingerprint().describe() + "), as is usual"
        + " in a container: an activation bound to it could not be checked later. Ask Hitachi Vantara for an"
        + " unbound (container) activation of this license key instead." );
      return 1;
    }
    String code = request.encode();
    if ( args.length == 4 ) {
      writeText( Paths.get( args[ 3 ] ), code );
      out.println( "Activation request written to " + args[ 3 ] + "." );
    } else {
      out.println( "Activation request for this machine:" );
      out.println();
      out.println( code );
    }
    out.println();
    out.println( "Send the request to Hitachi Vantara from any computer. It identifies this machine by hashes"
      + " only (" + request.getFingerprint().describe() + ")." );
    out.println( "Then install the activation code you receive: license install <activation code | file>" );
    return 0;
  }

  private int install( String[] args ) throws LicenseException {
    if ( args.length != 2 ) {
      err.println( "usage: license install <activation code | file>" );
      return 2;
    }
    String text = textOf( args[ 1 ] );
    if ( !text.trim().startsWith( Activation.PREFIX ) ) {
      err.println( "Not installing: this is not an activation code (it should start with " + Activation.PREFIX + ")."
        + " A license key is activated with: license request <license key>" );
      return 1;
    }
    LicenseValidationResult result = verifier.verify( text );
    if ( !result.isValid() ) {
      err.println( "Not installing: " + result.getMessage() );
      return 1;
    }
    LicenseStore.write( text.replaceAll( "\\s+", "" ) );
    out.println( "License installed: " + describe( result ) );
    return 0;
  }

  private int status() throws LicenseException {
    LicenseValidationResult result = LicenseEnforcement.check();
    out.println( ( result.isValid() ? "Licensed: " + describe( result ) : "Not licensed: " + result.getMessage() ) );
    out.println( "License file: " + LicenseStore.getLicenseFilePath() );
    if ( !result.isValid() && result.getStatus() != LicenseValidationResult.Status.MALFORMED ) {
      out.println( LicenseEnforcement.advice( result ) );
    }
    return result.isValid() ? 0 : 1;
  }

  private int deactivate() throws LicenseException {
    Optional<String> installed = LicenseStore.read();
    if ( !installed.isPresent() || !installed.get().startsWith( Activation.PREFIX ) ) {
      err.println( "No activation is installed in " + LicenseStore.getLicenseFilePath() + "." );
      return 1;
    }
    Activation activation = Activation.decode( installed.get() );
    LicenseValidationResult result = verifier.verify( activation );
    if ( result.getStatus() == LicenseValidationResult.Status.INVALID_SIGNATURE ) {
      err.println( "Not deactivating: " + result.getMessage() );
      return 1;
    }
    ReleaseCode release = new ReleaseCode( activation.getLicense().getLicenseId(), activation.getActivationId(),
      System.currentTimeMillis() );
    LicenseStore.remove();
    out.println( "License deactivated on this machine. Release code:" );
    out.println();
    out.println( release.encode() );
    out.println();
    out.println( "Send the release code to Hitachi Vantara to activate the license on another machine." );
    return 0;
  }

  private static String describe( LicenseValidationResult result ) {
    LicensePayload payload = result.getPayload();
    String expiry = payload.isPerpetual() ? "perpetual" : Instant.ofEpochMilli( payload.getExpiresAt() ).toString();
    Activation activation = result.getActivation();
    String binding = activation == null ? "" : activation.isBound()
      ? ", activated " + Instant.ofEpochMilli( activation.getActivatedAt() ) + " for this machine"
      : ", unbound activation";
    return "customer=" + payload.getCustomerId() + ", edition=" + payload.getEdition() + ", expires=" + expiry
      + ", license id=" + payload.getLicenseId() + binding;
  }

  /** The argument itself, or the content of the file it names. */
  static String textOf( String arg ) throws LicenseException {
    Path path;
    try {
      path = Paths.get( arg );
    } catch ( RuntimeException e ) {
      return arg;
    }
    if ( arg.length() < 300 && Files.isRegularFile( path ) ) {
      try {
        return new String( Files.readAllBytes( path ), StandardCharsets.UTF_8 ).trim();
      } catch ( IOException e ) {
        throw new LicenseException( "Could not read " + arg, e );
      }
    }
    return arg;
  }

  private static void writeText( Path path, String text ) throws LicenseException {
    try {
      Files.write( path, ( text + System.lineSeparator() ).getBytes( StandardCharsets.UTF_8 ) );
    } catch ( IOException e ) {
      throw new LicenseException( "Could not write " + path, e );
    }
  }

  private void printUsage() {
    out.println( "usage: license <request <license key> [--out <file>] | install <activation code> | status"
      + " | deactivate>" );
    out.println();
    out.println( "  request <key>       print the request code that activates this machine (no network needed)" );
    out.println( "  install <code>      install the activation code issued for this machine" );
    out.println( "  status              show whether this machine is licensed" );
    out.println( "  deactivate          remove the activation and print the release code, to move the license" );
    out.println();
    out.println( "Codes can be given inline or as the path of a file holding them." );
    out.println( "License file: " + LicenseStore.getLicenseFilePath() + "  (-D" + LicenseStore.FILE_PROPERTY + " or "
      + LicenseStore.FILE_ENV + " to change)" );
  }
}
