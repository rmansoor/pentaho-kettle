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
import static org.junit.Assert.assertFalse;
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
  public void requireLicensedThrowsOnceTheGracePeriodIsOver() throws Exception {
    long fifteenDaysAgo = System.currentTimeMillis() - 15L * 24 * 60 * 60 * 1000;
    LicensePayload expired = new LicensePayload( UUID.randomUUID(), "Acme Corp", "ce", 0, fifteenDaysAgo );
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

  private static final long DAY = 24L * 60 * 60 * 1000;

  private static LicenseValidationResult validFor( long expiresAt ) throws Exception {
    LicensePayload payload = new LicensePayload( UUID.randomUUID(), "Acme Corp", "ce", 0, expiresAt );
    return new LicenseVerifier().verify( Activation.sign( payload, UUID.randomUUID(), 0, null,
      TestKeys.devPrivateKey() ) );
  }

  @Test
  public void warnsInTheLastMonthBeforeExpiry() throws Exception {
    long now = System.currentTimeMillis();
    String warning = LicenseEnforcement.expiryWarning( validFor( now + 10 * DAY ), now ).get();
    assertTrue( warning, warning.contains( "in 10 days" ) && warning.contains( "renew" ) );
    assertTrue( LicenseEnforcement.expiryWarning( validFor( now + DAY / 2 ), now ).get().contains( "today" ) );
    assertFalse( LicenseEnforcement.expiryWarning( validFor( now + 40 * DAY ), now ).isPresent() );
    assertFalse( LicenseEnforcement.expiryWarning( validFor( 0 ), now ).isPresent() );
    assertFalse( LicenseEnforcement.expiryWarning( null, now ).isPresent() );
  }

  /** Expired three days ago: PDI still starts, for 11 more days, warning loudly. */
  @Test
  public void anExpiredActivationKeepsWorkingInTheGracePeriod() throws Exception {
    long now = System.currentTimeMillis();
    LicensePayload expired = new LicensePayload( UUID.randomUUID(), "Acme Corp", "ce", 0, now - 3 * DAY );
    LicenseStore.write( Activation.sign( expired, UUID.randomUUID(), 0, null, TestKeys.devPrivateKey() ).encode() );
    LicenseValidationResult result = LicenseEnforcement.requireLicensed();
    assertTrue( result.isInGracePeriod() );
    assertEquals( now - 3 * DAY + 14 * DAY, result.getGraceEndsAt() );
    String warning = LicenseEnforcement.expiryWarning( result, now ).get();
    assertTrue( warning, warning.contains( "EXPIRED" ) && warning.contains( "11 days left" ) );
    // an unexpired license isn't in a grace period
    assertFalse( validFor( now + DAY ).isInGracePeriod() );
  }

  @Test
  public void anExpiredLicenseSaysHowToRenew() throws Exception {
    LicensePayload expired = new LicensePayload( UUID.randomUUID(), "Acme Corp", "ce", 0, System.currentTimeMillis()
      - 15 * DAY );
    LicenseStore.write( Activation.sign( expired, UUID.randomUUID(), 0, null, TestKeys.devPrivateKey() ).encode() );
    try {
      LicenseEnforcement.requireLicensed();
      fail( "expected a LicenseException" );
    } catch ( LicenseException e ) {
      assertTrue( e.getMessage(), e.getMessage().contains( "renew" ) && e.getMessage().contains( ".helixact" ) );
    }
  }

  /** A renewal's activation file dropped next to a valid license replaces it; other files are left alone. */
  @Test
  public void aDroppedRenewalReplacesTheValidLicense() throws Exception {
    long now = System.currentTimeMillis();
    UUID licenseId = UUID.randomUUID();
    UUID activationId = UUID.randomUUID();
    LicensePayload current = new LicensePayload( licenseId, "Acme Corp", "ce", 0, now + 10 * DAY );
    LicenseStore.write( Activation.sign( current, activationId, 0, null, TestKeys.devPrivateKey() ).encode() );

    java.nio.file.Path kettle = java.nio.file.Paths.get( LicenseStore.kettleDirectory() );
    LicensePayload other = new LicensePayload( UUID.randomUUID(), "Acme Corp", "ce", 0, now + 400 * DAY );
    java.nio.file.Path otherFile = kettle.resolve( "other.helixact" );
    java.nio.file.Files.write( otherFile, Activation.sign( other, UUID.randomUUID(), 0, null,
      TestKeys.devPrivateKey() ).encode().getBytes( "UTF-8" ) );
    assertEquals( now + 10 * DAY, LicenseEnforcement.check().getPayload().getExpiresAt() );
    assertTrue( java.nio.file.Files.exists( otherFile ) );
    java.nio.file.Files.delete( otherFile );

    LicensePayload renewed = new LicensePayload( licenseId, "Acme Corp", "ce", now, now + 375 * DAY );
    java.nio.file.Path renewal = kettle.resolve( "renewal.helixact" );
    java.nio.file.Files.write( renewal, Activation.sign( renewed, activationId, 0, null,
      TestKeys.devPrivateKey() ).encode().getBytes( "UTF-8" ) );
    LicenseValidationResult result = LicenseEnforcement.check();
    assertTrue( result.isValid() );
    assertEquals( now + 375 * DAY, result.getPayload().getExpiresAt() );
    assertFalse( java.nio.file.Files.exists( renewal ) );
    assertEquals( now + 375 * DAY, LicenseEnforcement.check().getPayload().getExpiresAt() );
  }

  /** An unbound activation: valid on any machine. */
  private static String aValidKey() throws Exception {
    LicensePayload payload = new LicensePayload( UUID.randomUUID(), "Acme Corp", "ce", 0, 0 );
    return Activation.sign( payload, UUID.randomUUID(), 0, null, TestKeys.devPrivateKey() ).encode();
  }
}
