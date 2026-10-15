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

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** The customer's two steps as Spoon's activation window and 'license activate' run them, on this machine. */
public class ActivatorTest {

  @Rule
  public TemporaryFolder temp = new TemporaryFolder();

  private String originalKettleHome;

  @Before
  public void kettleHomeInATempDir() {
    originalKettleHome = System.getProperty( "KETTLE_HOME" );
    System.setProperty( "KETTLE_HOME", temp.getRoot().getAbsolutePath() );
  }

  @After
  public void restore() {
    if ( originalKettleHome == null ) {
      System.clearProperty( "KETTLE_HOME" );
    } else {
      System.setProperty( "KETTLE_HOME", originalKettleHome );
    }
  }

  private static LicenseKey aKey() throws Exception {
    return LicenseKey.sign( new LicensePayload( UUID.randomUUID(), "Acme Corp", "ce", 0, 0 ), TestKeys.devPrivateKey() );
  }

  /** What the issuer does with a request: sign an activation for the fingerprint in it. */
  private static String answer( String requestCode ) throws Exception {
    ActivationRequest request = ActivationRequest.decode( requestCode );
    return Activation.sign( request.getEntitlement().getPayload(), UUID.randomUUID(), 1L, request.getFingerprint(),
      TestKeys.devPrivateKey() ).encode();
  }

  @Test
  public void startThenCompleteActivatesThisMachine() throws Exception {
    Activator activator = new Activator();
    String request = activator.start( aKey().encode() );
    assertEquals( request, Activator.pending().get() );
    LicenseValidationResult result = activator.complete( answer( request ) );
    assertTrue( result.getMessage(), result.isValid() );
    assertFalse( "the pending request is done", Activator.pending().isPresent() );
    assertTrue( LicenseEnforcement.check().isValid() );
  }

  @Test
  public void theKeyAndTheAnswerCanBeFiles() throws Exception {
    File keyFile = temp.newFile( "acme.key" );
    Files.write( keyFile.toPath(), aKey().encode().getBytes( StandardCharsets.UTF_8 ) );
    Activator activator = new Activator();
    String request = activator.start( keyFile.getAbsolutePath() );
    File answerFile = temp.newFile( "acme.helixact" );
    Files.write( answerFile.toPath(), answer( request ).getBytes( StandardCharsets.UTF_8 ) );
    assertTrue( activator.complete( answerFile.getAbsolutePath() ).isValid() );
  }

  @Test
  public void aMissingFileIsReportedAsSuch() throws Exception {
    try {
      new Activator().start( "my.key" );
      fail( "expected a LicenseException" );
    } catch ( LicenseException e ) {
      assertTrue( e.getMessage(), e.getMessage().startsWith( "There is no file " ) );
      assertTrue( e.getMessage(), e.getMessage().contains( "HELIX1-" ) );
    }
  }

  @Test
  public void pastingTheKeyInsteadOfTheAnswerIsExplained() throws Exception {
    String key = aKey().encode();
    Activator activator = new Activator();
    activator.start( key );
    try {
      activator.complete( key );
      fail( "expected a LicenseException" );
    } catch ( LicenseException e ) {
      assertTrue( e.getMessage(), e.getMessage().startsWith( "That is a license key (HELIX1-), not an activation code." ) );
    }
  }

  @Test
  public void anAnswerForAnotherMachineIsNotInstalled() throws Exception {
    LicenseKey key = aKey();
    Activator activator = new Activator();
    activator.start( key.encode() );
    MachineFingerprint elsewhere = MachineFingerprint.of( key.getPayload().getLicenseId(),
      new MachineInfo( "windows", "other", "other-host", java.util.Collections.singletonList( "001122334455" ) ) );
    String code = Activation.sign( key.getPayload(), UUID.randomUUID(), 1L, elsewhere, TestKeys.devPrivateKey() )
      .encode();
    assertEquals( LicenseValidationResult.Status.WRONG_MACHINE, activator.complete( code ).getStatus() );
    assertFalse( LicenseStore.read().isPresent() );
    assertTrue( "the request stays pending", Activator.pending().isPresent() );
  }

  @Test
  public void anActivationFileDroppedIntoTheKettleFolderIsInstalledAtStartup() throws Exception {
    String request = new Activator().start( aKey().encode() );
    File kettleDir = new File( LicenseStore.kettleDirectory() );
    File dropped = new File( kettleDir, "acme.helixact" );
    Files.write( dropped.toPath(), answer( request ).getBytes( StandardCharsets.UTF_8 ) );
    assertTrue( LicenseEnforcement.check().isValid() );
    assertFalse( dropped.exists() );
    assertTrue( new File( kettleDir, "acme.helixact.installed" ).exists() );
    assertTrue( LicenseStore.read().isPresent() );
  }

  @Test
  public void aReleaseCodeWhereTheKeyGoesIsNamed() throws Exception {
    String release = new ReleaseCode( UUID.randomUUID(), UUID.randomUUID(), 0 ).encode();
    try {
      new Activator().start( release );
      fail( "expected a LicenseException" );
    } catch ( LicenseException e ) {
      assertEquals( "That is a release code (HELIXREL-), not a license key. License keys start with HELIX1-.",
        e.getMessage() );
    }
  }

  @Test
  public void cancelForgetsThePendingRequest() throws Exception {
    new Activator().start( aKey().encode() );
    Activator.cancel();
    assertFalse( Activator.pending().isPresent() );
  }
}
