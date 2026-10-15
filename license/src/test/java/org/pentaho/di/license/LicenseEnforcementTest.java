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

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Collections;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** End-to-end: this is what {@code KettleEnvironment.init()} actually calls at startup. */
public class LicenseEnforcementTest {

  @Rule
  public TemporaryFolder tempFolder = new TemporaryFolder();

  private String originalKettleHome;

  @Before
  public void redirectKettleHomeToATempDir() {
    originalKettleHome = System.getProperty( "KETTLE_HOME" );
    System.setProperty( "KETTLE_HOME", tempFolder.getRoot().getAbsolutePath() );
    System.clearProperty( LicenseEnforcement.LICENSE_KEY_SYSTEM_PROPERTY );
  }

  @After
  public void restoreKettleHome() {
    if ( originalKettleHome == null ) {
      System.clearProperty( "KETTLE_HOME" );
    } else {
      System.setProperty( "KETTLE_HOME", originalKettleHome );
    }
    System.clearProperty( LicenseEnforcement.LICENSE_KEY_SYSTEM_PROPERTY );
  }

  @Test( expected = LicenseException.class )
  public void requireLicensedThrowsWhenNothingIsInstalled() throws Exception {
    LicenseEnforcement.requireLicensed();
  }

  @Test
  public void requireLicensedPassesWithAValidInstalledKey() throws Exception {
    LicenseStore.write( aValidKey() );

    LicenseEnforcement.requireLicensed(); // must not throw
  }

  @Test
  public void requireLicensedPassesWithAnActivationForThisMachine() throws Exception {
    LicensePayload payload = new LicensePayload( UUID.randomUUID(), "Acme Corp", "ce", 0, 0 );
    MachineFingerprint here = MachineFingerprint.ofThisMachine( payload.getLicenseId() );
    LicenseStore.write( Activation.sign( payload, UUID.randomUUID(), 0, here, TestKeys.devPrivateKey() ).encode() );
    LicenseEnforcement.requireLicensed(); // must not throw
  }

  @Test
  public void requireLicensedRefusesAnActivationForAnotherMachine() throws Exception {
    LicensePayload payload = new LicensePayload( UUID.randomUUID(), "Acme Corp", "ce", 0, 0 );
    MachineFingerprint elsewhere = MachineFingerprint.of( payload.getLicenseId(), new MachineInfo(
      MachineInfo.osFamily( System.getProperty( "os.name" ) ), "another-machine-id", "another-host",
      Collections.singletonList( "0a0b0c0d0e0f" ) ) );
    LicenseStore.write( Activation.sign( payload, UUID.randomUUID(), 0, elsewhere, TestKeys.devPrivateKey() ).encode() );
    assertEquals( LicenseValidationResult.Status.WRONG_MACHINE, LicenseEnforcement.check().getStatus() );
    try {
      LicenseEnforcement.requireLicensed();
      fail( "expected a LicenseException" );
    } catch ( LicenseException e ) {
      assertTrue( e.getMessage(), e.getMessage().contains( "license.sh deactivate" ) );
    }
  }

  @Test
  public void anEntitlementKeyAloneDoesNotLicense() throws Exception {
    LicensePayload payload = new LicensePayload( UUID.randomUUID(), "Acme Corp", "ce", 0, 0 );
    LicenseStore.write( LicenseKey.sign( payload, TestKeys.devPrivateKey() ).encode() );
    assertEquals( LicenseValidationResult.Status.NOT_ACTIVATED, LicenseEnforcement.check().getStatus() );
  }

  @Test( expected = LicenseException.class )
  public void requireLicensedThrowsWithAnExpiredActivation() throws Exception {
    long yesterday = System.currentTimeMillis() - 24L * 60 * 60 * 1000;
    LicensePayload expired = new LicensePayload( UUID.randomUUID(), "Acme Corp", "ce", 0, yesterday );
    LicenseStore.write( Activation.sign( expired, UUID.randomUUID(), 0, null, TestKeys.devPrivateKey() ).encode() );
    LicenseEnforcement.requireLicensed();
  }

  @Test
  public void settingTheClockBackIsNoticedForATimeLimitedLicense() throws Exception {
    long now = System.currentTimeMillis();
    long nextYear = now + 365L * 24 * 60 * 60 * 1000;
    LicensePayload payload = new LicensePayload( UUID.randomUUID(), "Acme Corp", "ce", 0, nextYear );
    LicenseStore.write( Activation.sign( payload, UUID.randomUUID(), 0, null, TestKeys.devPrivateKey() ).encode() );
    LicenseVerifier verifier = new LicenseVerifier();
    assertTrue( LicenseEnforcement.check( verifier, now ).isValid() );           // records "seen now"
    long twoDaysAgo = now - 2L * 24 * 60 * 60 * 1000;
    assertEquals( LicenseValidationResult.Status.CLOCK_ROLLBACK,
      LicenseEnforcement.check( verifier, twoDaysAgo ).getStatus() );
    long anHourAgo = now - 60L * 60 * 1000;                                      // within the tolerance
    assertTrue( LicenseEnforcement.check( verifier, anHourAgo ).isValid() );
  }

  @Test
  public void theLicenseFileLocationCanBeOverridden() throws Exception {
    java.io.File file = new java.io.File( tempFolder.getRoot(), "service/license.txt" );
    System.setProperty( LicenseStore.FILE_PROPERTY, file.getAbsolutePath() );
    try {
      LicenseStore.write( aValidKey() );
      assertTrue( file.isFile() );
      LicenseEnforcement.requireLicensed();
    } finally {
      System.clearProperty( LicenseStore.FILE_PROPERTY );
    }
  }

  @Test
  public void systemPropertyOverridesTheInstalledFile() throws Exception {
    // an invalid key on disk, but a valid one via the system property (e.g. a scripted/container deploy)
    LicenseStore.write( "garbage" );
    System.setProperty( LicenseEnforcement.LICENSE_KEY_SYSTEM_PROPERTY, aValidKey() );

    LicenseEnforcement.requireLicensed(); // must not throw — the property wins
  }

  @Test
  public void checkReturnsAResultInsteadOfThrowing() throws Exception {
    LicenseValidationResult result = LicenseEnforcement.check();

    assertEquals( LicenseValidationResult.Status.NOT_INSTALLED, result.getStatus() );
  }

  @Test
  public void theExceptionMessagePointsAtTheFixCommand() {
    try {
      LicenseEnforcement.requireLicensed();
      fail( "expected a LicenseException" );
    } catch ( LicenseException e ) {
      assertTrue( e.getMessage(), e.getMessage().contains( "license.sh activate" ) );
    }
  }

  /** An unbound activation: valid on any machine. */
  private static String aValidKey() throws Exception {
    LicensePayload payload = new LicensePayload( UUID.randomUUID(), "Acme Corp", "ce", 0, 0 );
    return Activation.sign( payload, UUID.randomUUID(), 0, null, TestKeys.devPrivateKey() ).encode();
  }
}
