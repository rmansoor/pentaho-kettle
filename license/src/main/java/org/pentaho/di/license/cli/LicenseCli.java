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
import org.pentaho.di.license.Activator;
import org.pentaho.di.license.ActivationRequest;
import org.pentaho.di.license.LicenseEnforcement;
import org.pentaho.di.license.LicenseException;
import org.pentaho.di.license.LicenseKey;
import org.pentaho.di.license.LicensePayload;
import org.pentaho.di.license.LicenseStore;
import org.pentaho.di.license.LicenseValidationResult;
import org.pentaho.di.license.LicenseVerifier;
import org.pentaho.di.license.ReleaseCode;
import org.pentaho.di.license.Texts;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
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
 *   license activate        asks for the license key, prints the request for Hitachi Vantara, then asks for the
 *                           activation code (now, or when run again later)
 *   license status          shows whether this machine is licensed
 *   license deactivate      removes the activation and prints the release code, to move the license
 *   license request / install   the two steps of activate, for scripts
 * </pre>
 * Codes can be pasted, or given as the path of a file holding them.
 */
public class LicenseCli {

  private final PrintStream out;
  private final PrintStream err;
  private final LicenseVerifier verifier;
  private final BufferedReader in;

  LicenseCli( PrintStream out, PrintStream err, LicenseVerifier verifier, BufferedReader in ) {
    this.out = out;
    this.err = err;
    this.verifier = verifier;
    this.in = in;
  }

  public static void main( String[] args ) {
    int exitCode;
    try {
      exitCode = new LicenseCli( System.out, System.err, new LicenseVerifier(),
        new BufferedReader( new InputStreamReader( System.in, StandardCharsets.UTF_8 ) ) ).run( args );
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
        case "activate":
          return activate();
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

  /** Both steps, asking for what it needs; picks up a pending request when run again. */
  private int activate() throws LicenseException {
    Activator activator = new Activator();
    LicenseValidationResult now = LicenseEnforcement.check();
    if ( now.isValid() ) {
      out.println( "This machine is already licensed: " + describe( now ) );
      return 0;
    }
    String request = Activator.pending().orElse( null );
    if ( request != null ) {
      out.println( "You requested an activation earlier. Its request code, in case you still need to send it:" );
      out.println();
      out.println( request );
      out.println();
    } else {
      String key = ask( "Paste your license key (or the path of the file holding it), then press Enter."
        + "\nRenewed an expired license? Paste the activation code that came with the renewal instead:" );
      if ( key.isEmpty() ) {
        err.println( "No license key entered." );
        return 1;
      }
      if ( Activator.isActivationCode( key ) ) {
        return finish( activator.complete( key ) );
      }
      request = activator.start( key );
      Path saved = Paths.get( "activation-request.txt" ).toAbsolutePath();
      writeText( saved, request );
      out.println();
      out.println( "Your activation request:" );
      out.println();
      out.println( request );
      out.println();
      out.println( "It is also saved in " + saved + "." );
      out.println( "Send it to Hitachi Vantara from any computer (email or the activation portal)." );
      out.println();
    }
    String code = ask( "Paste the activation code you received (or the path of the file holding it) and press"
      + " Enter.\nNo code yet? Just press Enter, and run './license.sh activate' again when you have it:" );
    if ( code.isEmpty() ) {
      out.println( "Your request is kept. Run './license.sh activate' again when you have the activation code." );
      return 0;
    }
    return finish( activator.complete( code ) );
  }

  private int finish( LicenseValidationResult result ) {
    if ( !result.isValid() ) {
      err.println( "Not activated: " + result.getMessage() );
      return 1;
    }
    out.println( "Activated: " + describe( result ) );
    return 0;
  }

  private String ask( String question ) throws LicenseException {
    out.println( question );
    out.print( "> " );
    out.flush();
    try {
      String line = in.readLine();
      return line == null ? "" : line.trim();
    } catch ( IOException e ) {
      throw new LicenseException( "Could not read the answer", e );
    }
  }

  private int request( String[] args ) throws LicenseException {
    if ( args.length != 2 && !( args.length == 4 && args[ 2 ].equals( "--out" ) ) ) {
      err.println( "usage: license request <license key | file> [--out <file>]" );
      return 2;
    }
    String code = new Activator( verifier ).start( args[ 1 ] );
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
      + " only, no hardware ids." );
    out.println( "Then install the activation code you receive: license install <activation code | file>, or run"
      + " 'license activate' and paste it." );
    return 0;
  }

  private int install( String[] args ) throws LicenseException {
    if ( args.length != 2 ) {
      err.println( "usage: license install <activation code | file>" );
      return 2;
    }
    String text = Texts.codeOrFile( args[ 1 ], Activation.PREFIX, "activation code" );
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
    Activator.cancel();
    out.println( "License installed: " + describe( result ) );
    return 0;
  }

  private int status() throws LicenseException {
    LicenseValidationResult result = LicenseEnforcement.check();
    out.println( ( result.isValid() ? "Licensed: " + describe( result ) : "Not licensed: " + result.getMessage() ) );
    LicenseEnforcement.expiryWarning( result ).ifPresent( warning -> out.println( "Warning: " + warning ) );
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
    long days = payload.daysLeft( System.currentTimeMillis() );
    String expiry = payload.isPerpetual() ? "never (perpetual)" : payload.expiryDate()
      + ( days > 0 ? " (in " + days + ( days == 1 ? " day)" : " days)" ) : result.isInGracePeriod()
        ? " (EXPIRED; grace period until " + Instant.ofEpochMilli( result.getGraceEndsAt() ).atZone(
        java.time.ZoneOffset.UTC ).toLocalDate() + ")" : " (expired)" );
    Activation activation = result.getActivation();
    String binding = activation == null ? "" : activation.isBound()
      ? ", activated " + Instant.ofEpochMilli( activation.getActivatedAt() ) + " for this machine"
      : ", unbound activation";
    return "customer=" + payload.getCustomerId() + ", edition=" + payload.getEdition() + ", expires=" + expiry
      + ", license id=" + payload.getLicenseId() + binding;
  }

  private static void writeText( Path path, String text ) throws LicenseException {
    try {
      Files.write( path, ( text + System.lineSeparator() ).getBytes( StandardCharsets.UTF_8 ) );
    } catch ( IOException e ) {
      throw new LicenseException( "Could not write " + path, e );
    }
  }

  private void printUsage() {
    out.println( "usage: license <activate | status | deactivate>" );
    out.println();
    out.println( "  activate     activate this machine: asks for your license key, gives you a request to send to" );
    out.println( "               Hitachi Vantara, then asks for the activation code you receive (now or later)" );
    out.println( "  status       show whether this machine is licensed" );
    out.println( "  deactivate   remove the activation and print the release code, to move the license" );
    out.println();
    out.println( "For scripts: request <license key> [--out <file>] and install <activation code>, the two steps of"
      + " activate. Codes can be pasted, or given as the path of a file holding them." );
    out.println( "License file: " + LicenseStore.getLicenseFilePath() + "  (-D" + LicenseStore.FILE_PROPERTY + " or "
      + LicenseStore.FILE_ENV + " to change)" );
  }
}
