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
 * shared by Spoon, Carte, Pan, and Kitchen. Reads the locally installed license key and verifies it,
 * entirely offline.
 * <p>
 * This phase deliberately only answers "has this user purchased a license" (no seats, no machine
 * binding, no trial). {@link #requireLicensed()} is meant to be called once at startup; a future phase
 * can add a second, cheaper call at each transformation/job execution without changing this class's
 * contract.
 */
public final class LicenseEnforcement {

  /**
   * System property that, if set, is used instead of the on-disk license file. Mainly for tests and
   * for scripted/containerized deployments that prefer passing the key as {@code -D} rather than
   * pre-installing a file.
   */
  public static final String LICENSE_KEY_SYSTEM_PROPERTY = "helix.license.key";

  private LicenseEnforcement() {
  }

  /**
   * @throws LicenseException if no valid license key is installed. The message is meant to be shown
   *     to the user as-is (it names the CLI command to fix the problem).
   */
  public static void requireLicensed() throws LicenseException {
    LicenseValidationResult result = check();
    if ( !result.isValid() ) {
      throw new LicenseException( result.getMessage() + System.lineSeparator()
        + "Run 'license status' for details, or 'license install <key>' to install a purchased license." );
    }
  }

  /** Same check as {@link #requireLicensed()}, without throwing — for the CLI's 'status' command. */
  public static LicenseValidationResult check() throws LicenseException {
    Optional<String> keyString = readInstalledKey();
    if ( !keyString.isPresent() ) {
      return LicenseValidationResult.malformed( "No license key is installed" );
    }
    return new LicenseVerifier().verify( keyString.get() );
  }

  private static Optional<String> readInstalledKey() throws LicenseException {
    String fromProperty = System.getProperty( LICENSE_KEY_SYSTEM_PROPERTY );
    if ( fromProperty != null && !fromProperty.trim().isEmpty() ) {
      return Optional.of( fromProperty );
    }
    return LicenseStore.read();
  }
}
