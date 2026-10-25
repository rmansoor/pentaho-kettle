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
import org.pentaho.di.license.Activation;
import org.pentaho.di.license.ActivationRequest;
import org.pentaho.di.license.ActivationRequestFactory;
import org.pentaho.di.license.InstanceLimit;
import org.pentaho.di.license.LicenseException;
import org.pentaho.di.license.LicenseKey;
import org.pentaho.di.license.LicensePayload;
import org.pentaho.di.license.LicenseTerm;
import org.pentaho.di.license.LicenseValidationResult;
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
    LicenseKey key = issuer.issue( "Acme Corp", "ce", LicenseTerm.PERPETUAL );
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
    LicenseKey key = issuer.issue( "Acme Corp", "ce", LicenseTerm.PERPETUAL );
    LicenseKey other = issuer.issue( "Other Corp", "ce", LicenseTerm.PERPETUAL );
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
    LicenseKey key = issuer.issue( "Acme Corp", "ce", LicenseTerm.PERPETUAL );
    Issuer.Activated first = issuer.activate( request( key, "a" ) );
    try {
      issuer.releaseById( first.activation.getActivationId().toString(), " " );
      fail( "expected a refusal" );
    } catch ( IllegalArgumentException e ) {
      // refused
    }
    assertTrue( issuer.releaseById( first.activation.getActivationId().toString(), "disk failed" ).released );
  }

  @Test
  public void issuesForATermOfYears() throws Exception {
    LicensePayload p = issuer.issue( "Acme Corp", "ee", LicenseTerm.years( 2 ) ).getPayload();
    assertEquals( LicenseTerm.years( 2 ).expiresAt( p.getIssuedAt() ), p.getExpiresAt() );
    assertTrue( issuer.ledger().entries().get( 0 ).note.startsWith( "2 years, expires " ) );
    assertTrue( issuer.issue( "Acme Corp", "ee", LicenseTerm.PERPETUAL ).getPayload().isPerpetual() );
  }

  /** The new term starts at the old expiry, the license id stays, and activated machines get a new code. */
  @Test
  public void aRenewalAddsTheTermAndReissuesActivations() throws Exception {
    LicenseKey key = issuer.issue( "Acme Corp", "ee", LicenseTerm.years( 1 ) );
    LicensePayload old = key.getPayload();
    Issuer.Activated first = issuer.activate( request( key, "a" ) );

    Issuer.Renewed renewed = issuer.renew( key, LicenseTerm.years( 2 ) );
    LicensePayload now = renewed.key.getPayload();
    assertEquals( old.getLicenseId(), now.getLicenseId() );
    assertEquals( "Acme Corp", now.getCustomerId() );
    assertEquals( "ee", now.getEdition() );
    assertEquals( LicenseTerm.years( 2 ).expiresAt( old.getExpiresAt() ), now.getExpiresAt() );
    assertEquals( old.getExpiresAt(), renewed.previousExpiry );

    assertEquals( 1, renewed.activations.size() );
    Activation again = Activation.decode( renewed.activations.get( 0 ).code );
    assertEquals( first.activation.getActivationId(), again.getActivationId() );
    assertEquals( first.activation.getFingerprint(), again.getFingerprint() );
    assertEquals( now, again.getLicense() );
    // genuine and unexpired (for a made-up machine, so not this one)
    assertEquals( LicenseValidationResult.Status.WRONG_MACHINE, new LicenseVerifier().verify( again ).getStatus() );

    String licenseId = old.getLicenseId().toString();
    assertEquals( 1, issuer.ledger().active( licenseId ).size() );
    List<Ledger.Entry> entries = issuer.ledger().entries();
    assertEquals( "renewed", entries.get( entries.size() - 1 ).event );
    // the renewed key is still one license: another machine is refused
    try {
      issuer.activate( request( renewed.key, "b" ) );
      fail( "expected a refusal" );
    } catch ( LicenseException e ) {
      assertTrue( e.getMessage(), e.getMessage().contains( "already activated" ) );
    }
    // and the same machine with the renewed key replaces its activation
    assertEquals( 1, issuer.activate( request( renewed.key, "a" ) ).replaced.size() );
  }

  @Test
  public void anExpiredLicenseIsRenewedFromToday() throws Exception {
    LicenseKey expired = issuer.issue( "Acme Corp", "ce", Duration.ofDays( -3 ) );
    long before = System.currentTimeMillis();
    Issuer.Renewed renewed = issuer.renew( expired, LicenseTerm.years( 1 ) );
    long expiresAt = renewed.key.getPayload().getExpiresAt();
    assertTrue( expiresAt >= LicenseTerm.years( 1 ).expiresAt( before ) );
    assertTrue( expiresAt <= LicenseTerm.years( 1 ).expiresAt( System.currentTimeMillis() ) );
    assertTrue( renewed.activations.isEmpty() );
  }

  @Test
  public void renewingToPerpetualAndRenewingAPerpetualLicense() throws Exception {
    LicenseKey key = issuer.issue( "Acme Corp", "ce", LicenseTerm.years( 1 ) );
    LicenseKey perpetual = issuer.renew( key, LicenseTerm.PERPETUAL ).key;
    assertTrue( perpetual.getPayload().isPerpetual() );
    try {
      issuer.renew( perpetual, LicenseTerm.years( 1 ) );
      fail( "expected a refusal" );
    } catch ( LicenseException e ) {
      assertTrue( e.getMessage(), e.getMessage().contains( "perpetual" ) );
    }
  }

  /** Two issuers on one ledger (the portal and the command line) never activate one license twice. */
  @Test
  public void concurrentActivationsOfOneLicenseGiveOneActivation() throws Exception {
    LicenseKey key = issuer.issue( "Acme Corp", "ce", LicenseTerm.PERPETUAL );
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

  private static final InstanceLimit FOLDER_300 = InstanceLimit.of( 300, InstanceLimit.Counting.FOLDER, false );

  @Test
  public void issuesALicenseWithAnInstanceLimit() throws Exception {
    LicenseKey key = issuer.issue( "Acme Corp", "ee", LicenseTerm.years( 1 ), FOLDER_300 );
    assertEquals( FOLDER_300, key.getPayload().getInstanceLimit() );
    assertTrue( new LicenseVerifier().verifyEntitlement( key ).isValid() );
    List<Ledger.Entry> entries = issuer.ledger().entries();
    String note = entries.get( entries.size() - 1 ).note;
    assertTrue( note, note.endsWith( "; instances: up to 300 at once, counted in a shared folder (over the limit:"
      + " refused)" ) );
    // and its activations carry it
    assertEquals( FOLDER_300, issuer.activateUnbound( key ).activation.getLicense().getInstanceLimit() );
  }

  @Test
  public void aRenewalKeepsTheLimitUnlessGivenOne() throws Exception {
    LicenseKey key = issuer.issue( "Acme Corp", "ee", LicenseTerm.years( 1 ), FOLDER_300 );
    LicenseKey kept = issuer.renew( key, LicenseTerm.years( 1 ) ).key;
    assertEquals( FOLDER_300, kept.getPayload().getInstanceLimit() );
    InstanceLimit more = InstanceLimit.of( 500, InstanceLimit.Counting.FOLDER, true );
    assertEquals( more, issuer.renew( kept, LicenseTerm.years( 1 ), more ).key.getPayload().getInstanceLimit() );
    assertEquals( InstanceLimit.NONE, issuer.renew( kept, LicenseTerm.years( 1 ), InstanceLimit.NONE ).key
      .getPayload().getInstanceLimit() );
  }

  /** More processes mid-term: the same expiry, a new key, and new codes for the activations. */
  @Test
  public void changingTheLimitKeepsTheExpiryAndReissuesActivations() throws Exception {
    LicenseKey key = issuer.issue( "Acme Corp", "ee", LicenseTerm.years( 1 ), FOLDER_300 );
    Issuer.Activated unbound = issuer.activateUnbound( key );
    InstanceLimit more = InstanceLimit.of( 500, InstanceLimit.Counting.FOLDER, false );

    Issuer.Renewed changed = issuer.change( key, more );
    assertEquals( key.getPayload().getExpiresAt(), changed.key.getPayload().getExpiresAt() );
    assertEquals( key.getPayload().getLicenseId(), changed.key.getPayload().getLicenseId() );
    assertEquals( more, changed.key.getPayload().getInstanceLimit() );
    assertEquals( 1, changed.activations.size() );
    Activation again = Activation.decode( changed.activations.get( 0 ).code );
    assertEquals( unbound.activation.getActivationId(), again.getActivationId() );
    assertEquals( more, again.getLicense().getInstanceLimit() );
    assertTrue( new LicenseVerifier().verify( again ).isValid() );

    List<Ledger.Entry> entries = issuer.ledger().entries();
    Ledger.Entry last = entries.get( entries.size() - 1 );
    assertEquals( "renewed", last.event );
    assertTrue( last.note, last.note.startsWith( "changed: expires " ) );
    assertTrue( last.note, last.note.contains( "instances: up to 500 at once" ) && last.note.contains(
      "(was up to 300 at once" ) );

    try {
      issuer.change( changed.key, more );
      fail( "expected a refusal" );
    } catch ( LicenseException e ) {
      assertTrue( e.getMessage(), e.getMessage().contains( "already allows up to 500" ) );
    }
  }

  @Test
  public void anExpiredLicenseIsRenewedNotChanged() throws Exception {
    LicenseKey expired = issuer.issue( "Acme Corp", "ce", Duration.ofMillis( 1 ) );
    Thread.sleep( 5 );
    try {
      issuer.change( expired, FOLDER_300 );
      fail( "expected a refusal" );
    } catch ( LicenseException e ) {
      assertTrue( e.getMessage(), e.getMessage().contains( "has expired: renew it" ) );
    }
  }

  @Test( expected = IllegalArgumentException.class )
  public void countingByAServerIsntAvailableYet() throws Exception {
    issuer.issue( "Acme Corp", "ce", LicenseTerm.years( 1 ),
      InstanceLimit.of( 3, InstanceLimit.Counting.SERVER, false ) );
  }
}
