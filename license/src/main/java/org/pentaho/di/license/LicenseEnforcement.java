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

import java.util.Optional;

/**
 * Single entry point called from {@code KettleEnvironment.init()} &mdash; the one bootstrap chokepoint
 * shared by Spoon, Carte, Pan, and Kitchen. Reads the installed license and verifies it, entirely offline:
 * a genuine, unexpired {@link Activation} for this machine (or an unbound one). For a license limited to a number
 * of PDI processes at once and counted in a shared folder, it also takes one of the slots ({@link InstanceSlots}).
 * <p>
 * Where the license comes from, first found wins: system property {@value #LICENSE_KEY_SYSTEM_PROPERTY} or
 * environment variable {@value #LICENSE_KEY_ENV} (the activation code itself), else the file of
 * {@link LicenseStore#getLicenseFilePath()}.
 */
public final class LicenseEnforcement {

  public static final String LICENSE_KEY_SYSTEM_PROPERTY = "helix.license.key";
  public static final String LICENSE_KEY_ENV = "HELIX_LICENSE";

  /** How far the clock may appear to go back (time zone and daylight-saving changes, clock corrections). */
  static final long CLOCK_TOLERANCE_MILLIS = 24L * 60 * 60 * 1000;
  /** Record the check time at most this often, so starting PDI doesn't write a file every time. */
  static final long SEEN_INTERVAL_MILLIS = 60L * 60 * 1000;
  /** Warn this many days before a license expires. */
  public static final int EXPIRY_WARNING_DAYS = 30;
  private static final long DAY_MILLIS = 24L * 60 * 60 * 1000;

  private LicenseEnforcement() {
  }

  /**
   * @return the valid license, for {@link #expiryWarning(LicenseValidationResult)} and
   *     {@link #instanceInfo(LicenseValidationResult)}
   * @throws LicenseException if this machine isn't licensed, or this process is over its license's limit of PDI
   *     processes at once. The message is meant to be shown to the user as-is (it names the command to fix the
   *     problem).
   */
  public static LicenseValidationResult requireLicensed() throws LicenseException {
    LicenseValidationResult result = check();
    if ( !result.isValid() ) {
      throw new LicenseException( result.getMessage() + System.lineSeparator() + advice( result ) );
    }
    countThisProcess( result );
    return result;
  }

  /** Takes this process's slot when the license is counted in a folder; refuses counting this PDI can't do. */
  static void countThisProcess( LicenseValidationResult result ) throws LicenseException {
    InstanceLimit limit = result.getPayload().getInstanceLimit();
    if ( !limit.isLimited() ) {
      return;
    }
    switch ( limit.getCounting() ) {
      case FOLDER:
        Activation activation = result.getActivation();
        InstanceSlots.take( result.getPayload(), activation != null && activation.isBound() );
        break;
      case SERVER:
        throw new LicenseException( "This license is counted by a license server, which this version of PDI doesn't"
          + " support: it needs a newer PDI." );
      default:
        break;
    }
  }

  /**
   * What to log at startup about the license's limit of PDI processes at once: the limit and, when counted, which
   * slot this process holds; empty for a license without a limit.
   */
  public static Optional<String> instanceInfo( LicenseValidationResult result ) {
    if ( result == null || !result.isValid() || !result.getPayload().getInstanceLimit().isLimited() ) {
      return Optional.empty();
    }
    String limit = "This license allows " + result.getPayload().getInstanceLimit().describe() + ".";
    return Optional.of( InstanceSlots.current().map( c -> limit + " This is " + c.describe() + "." ).orElse( limit ) );
  }

  /** A problem counting this process, to log as an error: over a soft limit, or not counted at all. */
  public static Optional<String> instanceWarning() {
    return InstanceSlots.current().flatMap( InstanceSlots.Claim::getWarning );
  }

  /**
   * A warning to show when a valid license expires within {@value #EXPIRY_WARNING_DAYS} days: what to tell the user,
   * or empty.
   */
  public static Optional<String> expiryWarning( LicenseValidationResult result ) {
    return expiryWarning( result, System.currentTimeMillis() );
  }

  static Optional<String> expiryWarning( LicenseValidationResult result, long now ) {
    if ( result == null || !result.isValid() || result.getPayload().isPerpetual() ) {
      return Optional.empty();
    }
    if ( result.isInGracePeriod() ) {
      long left = ( result.getGraceEndsAt() - now + DAY_MILLIS - 1 ) / DAY_MILLIS;
      return Optional.of( "This PDI license EXPIRED on " + result.getPayload().expiryDate() + ". PDI keeps working"
        + " for a grace period until " + java.time.Instant.ofEpochMilli( result.getGraceEndsAt() )
        .atZone( java.time.ZoneOffset.UTC ).toLocalDate() + " (" + ( left <= 1 ? "ends today" : left + " days left" )
        + "); after that it won't start. Ask Hitachi Vantara to renew it, and install the activation code that comes"
        + " with the renewal." );
    }
    long days = result.getPayload().daysLeft( now );
    if ( days > EXPIRY_WARNING_DAYS ) {
      return Optional.empty();
    }
    return Optional.of( "This PDI license expires on " + result.getPayload().expiryDate() + " (" + ( days <= 1
      ? "today" : "in " + days + " days" ) + "). Ask Hitachi Vantara to renew it: a renewal adds the new term to the"
      + " current one, and comes with an activation code to install on this machine. (After expiry, PDI keeps"
      + " working for a " + LicenseVerifier.GRACE_PERIOD_DAYS + "-day grace period.)" );
  }

  /** Same check as {@link #requireLicensed()}, without throwing — for the CLI's 'status' command. */
  public static LicenseValidationResult check() throws LicenseException {
    return check( new LicenseVerifier(), System.currentTimeMillis() );
  }

  static LicenseValidationResult check( LicenseVerifier verifier, long now ) throws LicenseException {
    Optional<String> installed = readInstalled();
    LicenseValidationResult result = installed.isPresent() ? verifier.verify( installed.get() )
      : LicenseValidationResult.notInstalled( LicenseStore.getLicenseFilePath().toString() );
    // an activation file dropped into the PDI or Kettle folder (servers without a desktop): an activation for an
    // unlicensed machine, or the renewal of the installed license
    Optional<LicenseValidationResult> dropped = new Activator( verifier ).installDropped( result );
    if ( dropped.isPresent() ) {
      // what is in effect now (a license given inline still comes first)
      Optional<LicenseValidationResult> inEffect = readInstalled().map( verifier::verify );
      if ( inEffect.isPresent() && inEffect.get().isValid() ) {
        result = inEffect.get();
      }
    }
    if ( result.isValid() && !result.getPayload().isPerpetual() ) {
      long seen = LicenseStore.lastSeen();
      if ( now + CLOCK_TOLERANCE_MILLIS < seen ) {
        return LicenseValidationResult.clockRollback( result.getPayload() );
      }
      if ( now > seen + SEEN_INTERVAL_MILLIS ) {
        LicenseStore.recordSeen( now );
      }
    }
    return result;
  }

  static Optional<String> readInstalled() throws LicenseException {
    String inline = LicenseStore.firstNonBlank( System.getProperty( LICENSE_KEY_SYSTEM_PROPERTY ),
      System.getenv( LICENSE_KEY_ENV ) );
    return inline != null ? Optional.of( inline ) : LicenseStore.read();
  }

  /** What the user can do about {@code result}, in a sentence or two. */
  public static String advice( LicenseValidationResult result ) {
    switch ( result.getStatus() ) {
      case NOT_INSTALLED:
      case NOT_ACTIVATED:
        return "Activate PDI: start Spoon and follow the activation window, or run './license.sh activate'"
          + " (License.bat activate on Windows).";
      case EXPIRED:
        return "Its " + LicenseVerifier.GRACE_PERIOD_DAYS + "-day grace period is over as well. Ask Hitachi"
          + " Vantara to renew the license, then install the activation code that comes with the"
          + " renewal: paste it in Spoon's activation window, run './license.sh install <code or file>', or drop the"
          + " .helixact file into the PDI folder.";
      case WRONG_MACHINE:
        return "Each activation is for one machine. To move the license, run './license.sh deactivate' on the"
          + " activated machine and send the release code to Hitachi Vantara; then activate this machine.";
      default:
        return "Run 'license status' for details.";
    }
  }
}
