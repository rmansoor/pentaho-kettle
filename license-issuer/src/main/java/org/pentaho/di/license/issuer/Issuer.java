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
import org.pentaho.di.license.InstanceLimit;
import org.pentaho.di.license.ActivationRequest;
import org.pentaho.di.license.LicenseException;
import org.pentaho.di.license.LicenseKey;
import org.pentaho.di.license.LicensePayload;
import org.pentaho.di.license.LicenseTerm;
import org.pentaho.di.license.LicenseValidationResult;
import org.pentaho.di.license.LicenseVerifier;
import org.pentaho.di.license.MachineFingerprint;
import org.pentaho.di.license.ReleaseCode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Issues license keys and activations and keeps the ledger: what {@link LicenseIssuer} does on the command line,
 * for programs (the license portal). Every decision about the ledger (is this license already activated?) and the
 * entries it leads to are made under the ledger's lock, so issuers sharing one ledger file stay consistent.
 * <p>
 * A license has one activation at a time. A new request for an activated license is refused, unless it comes
 * from the same machine (reinstalled, or the activation file lost): then the old activation is replaced.
 * <p>
 * A license runs for a {@link LicenseTerm} (years, or perpetual). {@link #renew(LicenseKey, LicenseTerm)} extends
 * it: the same license id, the new term added to the current expiry, and a new activation code for each machine
 * it is activated on, so customers renew without sending a new activation request.
 * <p>
 * A license may carry an {@link InstanceLimit}: how many PDI processes may run at once, and how they are counted.
 * Renewing keeps it unless a new one is given; {@link #change(LicenseKey, InstanceLimit)} changes it without renewing.
 */
public final class Issuer {

  /** An activation just made, and the activations of the same machine it replaced. */
  public static final class Activated {
    public final Activation activation;
    public final String code;
    public final List<String> replaced;

    Activated( Activation activation, List<String> replaced ) {
      this.activation = activation;
      this.code = activation.encode();
      this.replaced = replaced;
    }
  }

  /** What a release did. */
  public static final class Released {
    public final Ledger.Entry activation;
    /** false when the activation was already released (nothing was written) */
    public final boolean released;

    Released( Ledger.Entry activation, boolean released ) {
      this.activation = activation;
      this.released = released;
    }
  }

  /** A renewed license: its new key, and the activation codes replacing the ones its machines have. */
  public static final class Renewed {
    public final LicenseKey key;
    public final String code;
    /** the expiry before the renewal (epoch millis) */
    public final long previousExpiry;
    public final List<Reissued> activations;

    Renewed( LicenseKey key, long previousExpiry, List<Reissued> activations ) {
      this.key = key;
      this.code = key.encode();
      this.previousExpiry = previousExpiry;
      this.activations = activations;
    }
  }

  /** An active activation signed again for the renewed license: same activation id, same machine. */
  public static final class Reissued {
    public final Ledger.Entry activation;
    public final String code;

    Reissued( Ledger.Entry activation, String code ) {
      this.activation = activation;
      this.code = code;
    }
  }

  private final PrivateKey privateKey;
  private final LicenseVerifier verifier;
  private final Ledger ledger;

  /**
   * @param privateKey the signing key; may be null for an issuer that only releases and lists
   * @param verifier   checks license keys against the public half of {@code privateKey}
   */
  public Issuer( PrivateKey privateKey, LicenseVerifier verifier, Ledger ledger ) {
    this.privateKey = privateKey;
    this.verifier = verifier;
    this.ledger = ledger;
  }

  public Ledger ledger() {
    return ledger;
  }

  /** Loads an EC private key from a PKCS8 DER file. */
  public static PrivateKey privateKey( Path path ) throws LicenseException {
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

  /** A license for {@code term}: some years from now, or perpetual. */
  public LicenseKey issue( String customer, String edition, LicenseTerm term ) throws LicenseException, IOException {
    return issue( customer, edition, term, InstanceLimit.NONE );
  }

  /** A license for {@code term} allowing {@code limit} PDI processes at once ({@link InstanceLimit#NONE}: any). */
  public LicenseKey issue( String customer, String edition, LicenseTerm term, InstanceLimit limit )
    throws LicenseException, IOException {
    long issuedAt = System.currentTimeMillis();
    return issue( customer, edition, issuedAt, term.expiresAt( issuedAt ), term.toString(), limit );
  }

  /**
   * A license valid for a number of days or hours (trials, tests).
   *
   * @param validFor how long the license is valid, or null for a perpetual license
   */
  public LicenseKey issue( String customer, String edition, Duration validFor ) throws LicenseException, IOException {
    return issue( customer, edition, validFor, InstanceLimit.NONE );
  }

  public LicenseKey issue( String customer, String edition, Duration validFor, InstanceLimit limit )
    throws LicenseException, IOException {
    long issuedAt = System.currentTimeMillis();
    return validFor == null ? issue( customer, edition, LicenseTerm.PERPETUAL, limit )
      : issue( customer, edition, issuedAt, Instant.ofEpochMilli( issuedAt ).plus( validFor ).toEpochMilli(),
        validFor.toDays() + " days", limit );
  }

  private LicenseKey issue( String customer, String edition, long issuedAt, long expiresAt, String term,
                            InstanceLimit limit ) throws LicenseException, IOException {
    if ( customer == null || customer.trim().isEmpty() ) {
      throw new IllegalArgumentException( "a license needs a customer" );
    }
    requireSupported( limit );
    LicensePayload payload = new LicensePayload( UUID.randomUUID(), customer.trim(),
      edition == null || edition.trim().isEmpty() ? "ce" : edition.trim(), issuedAt, expiresAt, limit );
    LicenseKey key = LicenseKey.sign( payload, signingKey() );
    ledger.append( entry( "issued", payload, "", "", ( expiresAt == 0 ? "perpetual"
      : term + ", expires " + Instant.ofEpochMilli( expiresAt ) ) + instancesNote( limit ) ) );
    return key;
  }

  /** Counting by a license server (tier 3) isn't implemented: PDI would refuse such a license. */
  private static void requireSupported( InstanceLimit limit ) {
    if ( limit != null && limit.isLimited() && limit.getCounting() == InstanceLimit.Counting.SERVER ) {
      throw new IllegalArgumentException( "counting by a license server isn't available yet: use declared or folder" );
    }
  }

  private static String instancesNote( InstanceLimit limit ) {
    return limit.isLimited() ? "; instances: " + limit.describe() : "";
  }

  /**
   * Renews a license: the same license id, customer and edition, running {@code term} longer, counted from its
   * current expiry (or from now, when it has already expired). Renewing for {@link LicenseTerm#PERPETUAL} makes it
   * perpetual. Each active activation is signed again for the renewed license (same activation id and machine):
   * installing that code is all a customer does.
   *
   * @param current the license key as issued (or as last renewed)
   */
  public Renewed renew( LicenseKey current, LicenseTerm term ) throws LicenseException, IOException {
    return renew( current, term, null );
  }

  /**
   * Same, also changing the instance limit.
   *
   * @param limit the new limit ({@link InstanceLimit#NONE}: none), or null to keep the license's
   */
  public Renewed renew( LicenseKey current, LicenseTerm term, InstanceLimit limit )
    throws LicenseException, IOException {
    requireGenuine( current );
    requireSupported( limit );
    LicensePayload old = current.getPayload();
    if ( old.isPerpetual() ) {
      throw new LicenseException( "Refused: license " + old.getLicenseId() + " is perpetual, there is nothing to renew" );
    }
    long now = System.currentTimeMillis();
    LicensePayload renewed = new LicensePayload( old.getLicenseId(), old.getCustomerId(), old.getEdition(), now,
      term.expiresAt( Math.max( old.getExpiresAt(), now ) ), limit == null ? old.getInstanceLimit() : limit );
    StringBuilder note = new StringBuilder( term.isPerpetual() ? "renewed: perpetual"
      : "renewed for " + term + ": expires " + Instant.ofEpochMilli( renewed.getExpiresAt() ) );
    note.append( " (was " ).append( Instant.ofEpochMilli( old.getExpiresAt() ) ).append( ')' );
    return reissue( old, renewed, note );
  }

  /**
   * Changes a license's instance limit (more or fewer PDI processes at once), keeping its expiry: a new key, and a
   * new activation code for each machine it is activated on, as for a renewal.
   *
   * @param limit the new limit, or {@link InstanceLimit#NONE} for none
   */
  public Renewed change( LicenseKey current, InstanceLimit limit ) throws LicenseException, IOException {
    if ( requireGenuine( current ) == LicenseValidationResult.Status.EXPIRED ) {
      throw new LicenseException( "Refused: license " + current.getPayload().getLicenseId() + " has expired: renew it,"
        + " giving the new limit" );
    }
    requireSupported( limit );
    LicensePayload old = current.getPayload();
    if ( old.getInstanceLimit().equals( limit ) ) {
      throw new LicenseException( "Refused: license " + old.getLicenseId() + " already allows " + limit.describe() );
    }
    LicensePayload changed = new LicensePayload( old.getLicenseId(), old.getCustomerId(), old.getEdition(),
      System.currentTimeMillis(), old.getExpiresAt(), limit );
    return reissue( old, changed, new StringBuilder( "changed: expires " + changed.expiryDate() ) );
  }

  /** Valid, or merely expired (renewable): anything else is refused. */
  private LicenseValidationResult.Status requireGenuine( LicenseKey current ) throws LicenseException {
    LicenseValidationResult checked = verifier.verifyEntitlement( current );
    if ( !checked.isValid() && checked.getStatus() != LicenseValidationResult.Status.EXPIRED ) {
      throw new LicenseException( "Refused: " + checked.getMessage() );
    }
    return checked.getStatus();
  }

  /** Signs {@code renewed} and every active activation of {@code old} again, and records it ({@code renewed}). */
  private Renewed reissue( LicensePayload old, LicensePayload renewed, StringBuilder note )
    throws LicenseException, IOException {
    PrivateKey key = signingKey();
    if ( !old.getInstanceLimit().equals( renewed.getInstanceLimit() ) ) {
      note.append( "; instances: " ).append( renewed.getInstanceLimit().describe() ).append( " (was " )
        .append( old.getInstanceLimit().describe() ).append( ')' );
    }
    LicenseKey renewedKey = LicenseKey.sign( renewed, key );
    String licenseId = old.getLicenseId().toString();
    return ledger.update( ( entries, append ) -> {
      List<Reissued> reissued = new ArrayList<>();
      for ( Ledger.Entry active : Ledger.active( entries, licenseId ) ) {
        MachineFingerprint machine = active.binding.equals( "unbound" ) ? null
          : MachineFingerprint.decode( active.binding );
        Activation activation = Activation.sign( renewed, UUID.fromString( active.activationId ),
          Instant.parse( active.time ).toEpochMilli(), machine, key );
        reissued.add( new Reissued( active, activation.encode() ) );
      }
      for ( Reissued r : reissued ) {
        note.append( "; new code for activation " ).append( r.activation.activationId );
      }
      append.add( entry( "renewed", renewed, "", "", note.toString() ) );
      return new Renewed( renewedKey, old.getExpiresAt(), reissued );
    } );
  }

  /** Answers an activation request (HELIXREQ-...): an activation bound to the requesting machine. */
  public Activated activate( ActivationRequest request ) throws LicenseException, IOException {
    return activate( request.getEntitlement(), request.getFingerprint() );
  }

  /** An unbound activation (containers), which uses up the license like any other. */
  public Activated activateUnbound( LicenseKey key ) throws LicenseException, IOException {
    return activate( key, null );
  }

  private Activated activate( LicenseKey entitlement, MachineFingerprint fingerprint )
    throws LicenseException, IOException {
    PrivateKey key = signingKey();
    LicenseValidationResult result = verifier.verifyEntitlement( entitlement );
    if ( !result.isValid() ) {
      throw new LicenseException( "Refused: " + result.getMessage() );
    }
    LicensePayload license = entitlement.getPayload();
    String licenseId = license.getLicenseId().toString();
    return ledger.update( ( entries, append ) -> {
      List<Ledger.Entry> replaced = new ArrayList<>();
      for ( Ledger.Entry active : Ledger.active( entries, licenseId ) ) {
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
      List<String> replacedIds = new ArrayList<>();
      for ( Ledger.Entry old : replaced ) {
        append.add( new Ledger.Entry( Ledger.now(), "released", licenseId, old.activationId, old.customer,
          old.edition, old.binding, "replaced by " + activationId + " (same machine)" ) );
        replacedIds.add( old.activationId );
      }
      append.add( entry( "activated", license, activationId.toString(),
        fingerprint == null ? "unbound" : fingerprint.encode(),
        fingerprint == null ? "unbound activation" : fingerprint.describe() ) );
      return new Activated( activation, replacedIds );
    } );
  }

  /** Records the release code a customer's {@code license deactivate} printed. */
  public Released release( ReleaseCode code ) throws LicenseException, IOException {
    String activationId = code.getActivationId().toString();
    return release( activationId, code.getLicenseId().toString(),
      "release code from the customer, deactivated " + Instant.ofEpochMilli( code.getReleasedAt() ) );
  }

  /** Releases an activation without a release code (the machine is gone): only the issuer can decide this. */
  public Released releaseById( String activationId, String reason ) throws LicenseException, IOException {
    if ( reason == null || reason.trim().isEmpty() ) {
      throw new IllegalArgumentException( "a release without a release code needs a reason" );
    }
    return release( activationId, null, "released by the issuer: " + reason.trim() );
  }

  private Released release( String activationId, String licenseId, String note ) throws LicenseException, IOException {
    return ledger.update( ( entries, append ) -> {
      Ledger.Entry activated = Ledger.find( entries, activationId );
      if ( activated == null ) {
        throw new LicenseException( "No activation " + activationId + " in " + ledger.path() );
      }
      if ( licenseId != null && !activated.licenseId.equals( licenseId ) ) {
        throw new LicenseException( "Refused: the release code's license id doesn't match the ledger" );
      }
      boolean active = Ledger.active( entries, activated.licenseId ).stream()
        .anyMatch( e -> e.activationId.equals( activationId ) );
      if ( !active ) {
        return new Released( activated, false );
      }
      append.add( new Ledger.Entry( Ledger.now(), "released", activated.licenseId, activationId, activated.customer,
        activated.edition, activated.binding, note ) );
      return new Released( activated, true );
    } );
  }

  private PrivateKey signingKey() {
    if ( privateKey == null ) {
      throw new IllegalStateException( "this issuer has no private key: it can only release and list" );
    }
    return privateKey;
  }

  private static Ledger.Entry entry( String event, LicensePayload license, String activationId, String binding,
                                     String note ) {
    return new Ledger.Entry( Ledger.now(), event, license.getLicenseId().toString(), activationId,
      license.getCustomerId(), license.getEdition(), binding, note );
  }
}
