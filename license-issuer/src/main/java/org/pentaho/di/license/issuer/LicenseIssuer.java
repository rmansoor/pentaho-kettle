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

package org.pentaho.di.license.issuer;

import org.pentaho.di.license.ActivationRequest;
import org.pentaho.di.license.LicenseException;
import org.pentaho.di.license.LicenseKey;
import org.pentaho.di.license.LicenseTerm;
import org.pentaho.di.license.LicenseVerifier;
import org.pentaho.di.license.ReleaseCode;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Internal, offline tool that issues licenses and keeps the ledger (never shipped to customers: it needs the
 * private signing key).
 * <pre>
 *   license-issuer issue    --private-key K --ledger L --customer C [--edition ce] --term &lt;1y | 2y | ... | perpetual&gt;
 *   license-issuer renew    --private-key K --ledger L --term &lt;1y | ... | perpetual&gt; &lt;HELIX1-... | file&gt; [--out DIR]
 *   license-issuer activate --private-key K --ledger L &lt;HELIXREQ-... | file&gt;
 *   license-issuer activate --private-key K --ledger L --unbound &lt;HELIX1-... | file&gt;
 *   license-issuer release  --ledger L &lt;HELIXREL-... | file&gt;
 *   license-issuer release  --ledger L --activation-id ID --reason "..."
 *   license-issuer list     --ledger L [license id]
 * </pre>
 * {@code issue --expires-in-days N} instead of {@code --term} issues a trial of N days. A renewal keeps the license id
 * and adds the term to the current expiry; it prints the new license key and a new activation code for each machine
 * the license is activated on.
 * <p>
 * A license has one activation at a time. A new request for an activated license is refused, unless it comes
 * from the same machine (reinstalled, or the activation file lost): then the old activation is replaced.
 */
public final class LicenseIssuer {

  private final PrintStream out;
  private final Map<String, String> options;
  private final List<String> arguments;

  LicenseIssuer( PrintStream out, String[] args ) {
    this.out = out;
    this.options = new HashMap<>();
    this.arguments = new ArrayList<>();
    for ( int i = 0; i < args.length; i++ ) {
      String arg = args[ i ];
      if ( arg.equals( "--unbound" ) || arg.equals( "--perpetual" ) ) {
        options.put( arg.substring( 2 ), "true" );
      } else if ( arg.startsWith( "--" ) && i + 1 < args.length ) {
        options.put( arg.substring( 2 ), args[ ++i ] );
      } else {
        arguments.add( arg );
      }
    }
  }

  public static void main( String[] args ) {
    try {
      System.exit( new LicenseIssuer( System.out, args ).run() );
    } catch ( IllegalArgumentException | LicenseException | IOException e ) {
      System.err.println( "error: " + e.getMessage() );
      System.exit( e instanceof IllegalArgumentException ? 2 : 1 );
    }
  }

  int run() throws LicenseException, IOException {
    String command = arguments.isEmpty() ? "" : arguments.remove( 0 );
    switch ( command ) {
      case "issue":
        return issue();
      case "renew":
        return renew();
      case "activate":
        return activate();
      case "release":
        return release();
      case "list":
        return list();
      default:
        throw new IllegalArgumentException( "usage: license-issuer <issue | renew | activate | release | list> ...  "
          + "(see docs/licensing/README.md)" );
    }
  }

  private int issue() throws LicenseException, IOException {
    String customer = require( "customer" );
    String edition = options.getOrDefault( "edition", "ce" );
    if ( options.containsKey( "expires-in-days" ) && !options.containsKey( "term" ) ) {
      Duration trial = Duration.ofDays( Integer.parseInt( options.get( "expires-in-days" ) ) );
      emit( issuer( true ).issue( customer, edition, trial ).encode() );
    } else {
      emit( issuer( true ).issue( customer, edition, term() ).encode() );
    }
    return 0;
  }

  private int renew() throws LicenseException, IOException {
    LicenseTerm term = term();
    Issuer.Renewed renewed = issuer( true ).renew( LicenseKey.decode( text( single( "license key" ) ) ), term );
    LicenseKey key = renewed.key;
    out.println( "Renewed license " + key.getPayload().getLicenseId() + " (" + key.getPayload().getCustomerId()
      + "): expires " + key.getPayload().expiryDate() + ", was " + Instant.ofEpochMilli( renewed.previousExpiry )
      .atZone( ZoneOffset.UTC ).toLocalDate() + "." );
    Path dir = options.containsKey( "out" ) ? Paths.get( options.get( "out" ) ) : null;
    if ( dir != null ) {
      Files.createDirectories( dir );
    }
    out.println();
    out.println( "License key (replaces the old one, for activating machines from now on):" );
    write( dir, "license-key.txt", renewed.code );
    for ( Issuer.Reissued r : renewed.activations ) {
      out.println();
      out.println( "Activation code for activation " + r.activation.activationId + " (" + r.activation.note + "),"
        + " to install on that machine:" );
      write( dir, r.activation.activationId + ".helixact", r.code );
    }
    if ( renewed.activations.isEmpty() ) {
      out.println();
      out.println( "The license isn't activated anywhere: no activation codes to send." );
    }
    return 0;
  }

  /** --term (or --perpetual): required, so no license is perpetual by accident. */
  private LicenseTerm term() {
    if ( options.containsKey( "perpetual" ) ) {
      return LicenseTerm.PERPETUAL;
    }
    if ( !options.containsKey( "term" ) ) {
      throw new IllegalArgumentException( "say how long the license runs: --term 1y (2y, 3y, ...) or --term perpetual" );
    }
    return LicenseTerm.parse( options.get( "term" ) );
  }

  /** Prints {@code code}, and writes it to {@code dir/name} when there is a directory. */
  private void write( Path dir, String name, String code ) throws IOException {
    if ( dir == null ) {
      out.println( code );
      return;
    }
    Path file = dir.resolve( name );
    Files.write( file, ( code + System.lineSeparator() ).getBytes( StandardCharsets.UTF_8 ) );
    out.println( "  written to " + file );
  }

  private int activate() throws LicenseException, IOException {
    Issuer issuer = issuer( true );
    Issuer.Activated activated = options.containsKey( "unbound" )
      ? issuer.activateUnbound( LicenseKey.decode( text( single( "license key" ) ) ) )
      : issuer.activate( ActivationRequest.decode( text( single( "activation request" ) ) ) );
    if ( !activated.replaced.isEmpty() ) {
      System.err.println( "note: replaces activation " + activated.replaced.get( 0 ) + " on the same machine" );
    }
    emit( activated.code );
    return 0;
  }

  private int release() throws LicenseException, IOException {
    Issuer issuer = issuer( false );
    Issuer.Released released = options.containsKey( "activation-id" )
      ? issuer.releaseById( options.get( "activation-id" ), require( "reason" ) )
      : issuer.release( ReleaseCode.decode( text( single( "release code" ) ) ) );
    Ledger.Entry activated = released.activation;
    if ( !released.released ) {
      out.println( "Activation " + activated.activationId + " is already released." );
      return 0;
    }
    out.println( "Released activation " + activated.activationId + " of license " + activated.licenseId + " ("
      + activated.customer + "): it can be activated again." );
    return 0;
  }

  private int list() throws IOException {
    String only = arguments.isEmpty() ? null : arguments.get( 0 );
    for ( Ledger.Entry e : ledger().entries() ) {
      if ( only == null || e.licenseId.equals( only ) ) {
        out.println( String.join( "  ", e.time, e.event, e.licenseId, e.activationId.isEmpty() ? "-" : e.activationId,
          e.customer, e.edition, e.note ) );
      }
    }
    return 0;
  }

  /** Prints {@code code}, or writes it to --out. */
  private void emit( String code ) throws IOException {
    if ( options.containsKey( "out" ) ) {
      Files.write( Paths.get( options.get( "out" ) ),
        ( code + System.lineSeparator() ).getBytes( StandardCharsets.UTF_8 ) );
      out.println( "Written to " + options.get( "out" ) );
    } else {
      out.println( code );
    }
  }

  private Issuer issuer( boolean signs ) throws LicenseException, IOException {
    LicenseVerifier verifier = options.containsKey( "public-key" )
      ? new LicenseVerifier( LicenseVerifier.publicKey( Files.readAllBytes( Paths.get( options.get( "public-key" ) ) ) ) )
      : new LicenseVerifier();
    return new Issuer( signs ? Issuer.privateKey( Paths.get( require( "private-key" ) ) ) : null, verifier, ledger() );
  }

  private Ledger ledger() {
    return new Ledger( Paths.get( require( "ledger" ) ) );
  }

  private String require( String name ) {
    String value = options.get( name );
    if ( value == null || value.trim().isEmpty() ) {
      throw new IllegalArgumentException( "missing required option: --" + name );
    }
    return value;
  }

  private String single( String what ) {
    if ( arguments.size() != 1 ) {
      throw new IllegalArgumentException( "expected one " + what + " (or the file holding it)" );
    }
    return arguments.get( 0 );
  }

  /** The argument itself, or the content of the file it names. */
  private static String text( String arg ) throws IOException {
    if ( arg.length() >= 300 ) {
      return arg;
    }
    Path path;
    try {
      path = Paths.get( arg );
    } catch ( RuntimeException e ) {
      return arg;
    }
    return Files.isRegularFile( path ) ? new String( Files.readAllBytes( path ), StandardCharsets.UTF_8 ).trim() : arg;
  }
}
