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

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.pentaho.di.license.ActivationRequest;
import org.pentaho.di.license.ActivationRequestFactory;
import org.pentaho.di.license.LicenseException;
import org.pentaho.di.license.LicenseKey;
import org.pentaho.di.license.LicenseVerifier;
import org.pentaho.di.license.ReleaseCode;

import java.io.File;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** The issuer's API, as the license portal uses it. */
public class IssuerTest {

  private static final String DEV_KEY = "../license/src/test/resources/org/pentaho/di/license/dev-private-key.der";

  @Rule
  public TemporaryFolder temp = new TemporaryFolder();

  private Issuer issuer;

  @Before
  public void setUp() throws Exception {
    issuer = new Issuer( Issuer.privateKey( Paths.get( DEV_KEY ) ), new LicenseVerifier(),
      new Ledger( new File( temp.getRoot(), "ledger.tsv" ).toPath() ) );
  }

  private static ActivationRequest request( LicenseKey key, String machine ) throws Exception {
    return ActivationRequest.decode( ActivationRequestFactory.request( key,
      ActivationRequestFactory.machine( key, machine ) ) );
  }

  @Test
  public void issuesAnExpiringLicense() throws Exception {
    LicenseKey key = issuer.issue( "Acme Corp", "ee", Duration.ofDays( 30 ) );
    assertEquals( "Acme Corp", key.getPayload().getCustomerId() );
    assertEquals( "ee", key.getPayload().getEdition() );
    assertFalse( key.getPayload().isPerpetual() );
    assertEquals( "issued", issuer.ledger().entries().get( 0 ).event );
  }

  @Test
  public void releaseCodeThenActivateAnotherMachine() throws Exception {
    LicenseKey key = issuer.issue( "Acme Corp", "ce", null );
    Issuer.Activated first = issuer.activate( request( key, "a" ) );
    Issuer.Released released = issuer.release( new ReleaseCode( key.getPayload().getLicenseId(),
      first.activation.getActivationId(), 1L ) );
    assertTrue( released.released );
    assertFalse( issuer.release( new ReleaseCode( key.getPayload().getLicenseId(),
      first.activation.getActivationId(), 1L ) ).released );
    issuer.activate( request( key, "b" ) );
  }

  @Test
  public void aReleaseCodeOfAnotherLicenseIsRefused() throws Exception {
    LicenseKey key = issuer.issue( "Acme Corp", "ce", null );
    LicenseKey other = issuer.issue( "Other Corp", "ce", null );
    Issuer.Activated first = issuer.activate( request( key, "a" ) );
    try {
      issuer.release( new ReleaseCode( other.getPayload().getLicenseId(), first.activation.getActivationId(), 1L ) );
      fail( "expected a refusal" );
    } catch ( LicenseException e ) {
      assertTrue( e.getMessage(), e.getMessage().contains( "doesn't match" ) );
    }
  }

  @Test
  public void aReleaseWithoutCodeNeedsAReason() throws Exception {
    LicenseKey key = issuer.issue( "Acme Corp", "ce", null );
    Issuer.Activated first = issuer.activate( request( key, "a" ) );
    try {
      issuer.releaseById( first.activation.getActivationId().toString(), " " );
      fail( "expected a refusal" );
    } catch ( IllegalArgumentException e ) {
      // refused
    }
    assertTrue( issuer.releaseById( first.activation.getActivationId().toString(), "disk failed" ).released );
  }

  /** Two issuers on one ledger (the portal and the command line) never activate one license twice. */
  @Test
  public void concurrentActivationsOfOneLicenseGiveOneActivation() throws Exception {
    LicenseKey key = issuer.issue( "Acme Corp", "ce", null );
    List<Callable<Boolean>> tasks = new ArrayList<>();
    for ( int i = 0; i < 8; i++ ) {
      ActivationRequest request = request( key, "m" + i );
      Issuer separate = new Issuer( Issuer.privateKey( Paths.get( DEV_KEY ) ), new LicenseVerifier(),
        new Ledger( issuer.ledger().path() ) );
      tasks.add( () -> {
        try {
          separate.activate( request );
          return true;
        } catch ( LicenseException e ) {
          return false;
        }
      } );
    }
    ExecutorService pool = Executors.newFixedThreadPool( 8 );
    int won = 0;
    try {
      for ( Future<Boolean> f : pool.invokeAll( tasks ) ) {
        won += f.get() ? 1 : 0;
      }
    } finally {
      pool.shutdown();
    }
    assertEquals( 1, won );
    assertEquals( 1, issuer.ledger().active( key.getPayload().getLicenseId().toString() ).size() );
  }
}
