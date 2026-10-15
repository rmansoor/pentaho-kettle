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

import org.pentaho.di.license.Activation;
import org.pentaho.di.license.ActivationRequest;
import org.pentaho.di.license.LicenseException;
import org.pentaho.di.license.LicenseKey;
import org.pentaho.di.license.LicensePayload;
import org.pentaho.di.license.LicenseValidationResult;
import org.pentaho.di.license.LicenseVerifier;
import org.pentaho.di.license.MachineFingerprint;
import org.pentaho.di.license.ReleaseCode;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Internal, offline tool that issues licenses and keeps the ledger (never shipped to customers: it needs the
 * private signing key).
 * <pre>
 *   license-issuer issue    --private-key K --ledger L --customer C [--edition ce] [--expires-in-days N]
 *   license-issuer activate --private-key K --ledger L &lt;HELIXREQ-... | file&gt;
 *   license-issuer activate --private-key K --ledger L --unbound &lt;HELIX1-... | file&gt;
 *   license-issuer release  --ledger L &lt;HELIXREL-... | file&gt;
 *   license-issuer release  --ledger L --activation-id ID --reason "..."
 *   license-issuer list     --ledger L [license id]
 * </pre>
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
      case "activate":
        return activate();
      case "release":
        return release();
      case "list":
        return list();
      default:
        throw new IllegalArgumentException( "usage: license-issuer <issue | activate | release | list> ...  "
          + "(see docs/licensing/README.md)" );
    }
  }

  private int issue() throws LicenseException, IOException {
    PrivateKey key = privateKey();
    Ledger ledger = ledger();
    long issuedAt = System.currentTimeMillis();
    long expiresAt = 0;
    if ( options.containsKey( "expires-in-days" ) ) {
      expiresAt = Instant.ofEpochMilli( issuedAt ).plus( Duration.ofDays(
        Integer.parseInt( options.get( "expires-in-days" ) ) ) ).toEpochMilli();
    }
    LicensePayload payload = new LicensePayload( UUID.randomUUID(), require( "customer" ),
      options.getOrDefault( "edition", "ce" ), issuedAt, expiresAt );
    String encoded = LicenseKey.sign( payload, key ).encode();
    ledger.append( entry( "issued", payload, "", "", expiresAt == 0 ? "perpetual"
      : "expires " + Instant.ofEpochMilli( expiresAt ) ) );
    emit( encoded );
    return 0;
  }

  private int activate() throws LicenseException, IOException {
    PrivateKey key = privateKey();
    Ledger ledger = ledger();
    boolean unbound = options.containsKey( "unbound" );
    LicenseKey entitlement;
    MachineFingerprint fingerprint = null;
    if ( unbound ) {
      entitlement = LicenseKey.decode( text( single( "license key" ) ) );
    } else {
      ActivationRequest request = ActivationRequest.decode( text( single( "activation request" ) ) );
      entitlement = request.getEntitlement();
      fingerprint = request.getFingerprint();
    }
    LicenseValidationResult result = verifier().verifyEntitlement( entitlement );
    if ( !result.isValid() ) {
      throw new LicenseException( "Refused: " + result.getMessage() );
    }
    LicensePayload license = entitlement.getPayload();
    String licenseId = license.getLicenseId().toString();

    List<Ledger.Entry> replaced = new ArrayList<>();
    for ( Ledger.Entry active : ledger.active( licenseId ) ) {
      boolean sameMachine = fingerprint != null && !active.binding.equals( "unbound" )
        && MachineFingerprint.decode( active.binding ).matches( fingerprint );
      if ( !sameMachine ) {
        throw new LicenseException( "Refused: license " + licenseId + " (" + license.getCustomerId()
          + ") is already activated: activation " + active.activationId + " of " + active.time
          + ( active.binding.equals( "unbound" ) ? " (unbound)" : "" ) + ". It can be activated again once that"
          + " activation is released: the customer runs 'license deactivate' and sends the release code, or"
          + " 'license-issuer release --activation-id " + active.activationId + " --reason ...'." );
      }
      replaced.add( active );
    }

    UUID activationId = UUID.randomUUID();
    Activation activation = Activation.sign( license, activationId, System.currentTimeMillis(), fingerprint, key );
    List<Ledger.Entry> entries = new ArrayList<>();
    for ( Ledger.Entry old : replaced ) {
      entries.add( new Ledger.Entry( Ledger.now(), "released", licenseId, old.activationId, old.customer,
        old.edition, old.binding, "replaced by " + activationId + " (same machine)" ) );
    }
    entries.add( entry( "activated", license, activationId.toString(),
      fingerprint == null ? "unbound" : fingerprint.encode(),
      fingerprint == null ? "unbound activation" : fingerprint.describe() ) );
    ledger.append( entries.toArray( new Ledger.Entry[ 0 ] ) );
    if ( !replaced.isEmpty() ) {
      System.err.println( "note: replaces activation " + replaced.get( 0 ).activationId + " on the same machine" );
    }
    emit( activation.encode() );
    return 0;
  }

  private int release() throws LicenseException, IOException {
    Ledger ledger = ledger();
    String activationId;
    String note;
    if ( options.containsKey( "activation-id" ) ) {
      activationId = options.get( "activation-id" );
      note = "released by the issuer: " + require( "reason" );
    } else {
      ReleaseCode code = ReleaseCode.decode( text( single( "release code" ) ) );
      activationId = code.getActivationId().toString();
      note = "release code from the customer, deactivated " + Instant.ofEpochMilli( code.getReleasedAt() );
      Ledger.Entry e = ledger.find( activationId );
      if ( e != null && !e.licenseId.equals( code.getLicenseId().toString() ) ) {
        throw new LicenseException( "Refused: the release code's license id doesn't match the ledger" );
      }
    }
    Ledger.Entry activated = ledger.find( activationId );
    if ( activated == null ) {
      throw new LicenseException( "No activation " + activationId + " in " + ledger.path() );
    }
    boolean active = ledger.active( activated.licenseId ).stream().anyMatch( e -> e.activationId.equals( activationId ) );
    if ( !active ) {
      out.println( "Activation " + activationId + " is already released." );
      return 0;
    }
    ledger.append( new Ledger.Entry( Ledger.now(), "released", activated.licenseId, activationId, activated.customer,
      activated.edition, activated.binding, note ) );
    out.println( "Released activation " + activationId + " of license " + activated.licenseId + " ("
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

  private Ledger.Entry entry( String event, LicensePayload license, String activationId, String binding, String note ) {
    return new Ledger.Entry( Ledger.now(), event, license.getLicenseId().toString(), activationId,
      license.getCustomerId(), license.getEdition(), binding, note );
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

  private LicenseVerifier verifier() throws LicenseException, IOException {
    return options.containsKey( "public-key" )
      ? new LicenseVerifier( LicenseVerifier.publicKey( Files.readAllBytes( Paths.get( options.get( "public-key" ) ) ) ) )
      : new LicenseVerifier();
  }

  private Ledger ledger() {
    return new Ledger( Paths.get( require( "ledger" ) ) );
  }

  private PrivateKey privateKey() throws LicenseException {
    Path path = Paths.get( require( "private-key" ) );
    byte[] der;
    try {
      der = Files.readAllBytes( path );
    } catch ( IOException e ) {
      throw new LicenseException( "Could not read private key file: " + path, e );
    }
    try {
      return KeyFactory.getInstance( "EC" ).generatePrivate( new PKCS8EncodedKeySpec( der ) );
    } catch ( NoSuchAlgorithmException | InvalidKeySpecException e ) {
      throw new LicenseException( "Could not load private key (expected PKCS8 DER, EC): " + path, e );
    }
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
