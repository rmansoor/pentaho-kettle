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
 * a genuine, unexpired {@link Activation} for this machine (or an unbound one).
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

  private LicenseEnforcement() {
  }

  /**
   * @throws LicenseException if this machine isn't licensed. The message is meant to be shown to the user as-is
   *     (it names the command to fix the problem).
   */
  public static void requireLicensed() throws LicenseException {
    LicenseValidationResult result = check();
    if ( !result.isValid() ) {
      throw new LicenseException( result.getMessage() + System.lineSeparator() + advice( result ) );
    }
  }

  /** Same check as {@link #requireLicensed()}, without throwing — for the CLI's 'status' command. */
  public static LicenseValidationResult check() throws LicenseException {
    return check( new LicenseVerifier(), System.currentTimeMillis() );
  }

  static LicenseValidationResult check( LicenseVerifier verifier, long now ) throws LicenseException {
    Optional<String> installed = readInstalled();
    if ( !installed.isPresent() ) {
      return LicenseValidationResult.notInstalled( LicenseStore.getLicenseFilePath().toString() );
    }
    LicenseValidationResult result = verifier.verify( installed.get() );
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
        return "Activate PDI with 'license request <license key>', send the request code it prints to Hitachi "
          + "Vantara, then install the activation code you receive with 'license install <activation code>'.";
      case WRONG_MACHINE:
        return "Each activation is for one machine. To move the license, run 'license deactivate' on the activated "
          + "machine and send the release code to Hitachi Vantara; then activate this machine with 'license request'.";
      default:
        return "Run 'license status' for details.";
    }
  }
}
