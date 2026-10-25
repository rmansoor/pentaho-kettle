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
import org.pentaho.di.license.LicenseException;
import org.pentaho.di.license.InstanceLimit;
import org.pentaho.di.license.LicenseKey;
import org.pentaho.di.license.MachineFingerprint;
import org.pentaho.di.license.ReleaseCode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The issuer against a real ledger file, signing with the repository's development key (the one the
 * kettle-license jar embeds), so what it issues verifies like a customer's PDI would verify it.
 */
public class LicenseIssuerTest {

  /** Test-only private half of the embedded development key (see docs/licensing/README.md). */
  private static final String DEV_KEY = "../license/src/test/resources/org/pentaho/di/license/dev-private-key.der";

  @Rule
  public TemporaryFolder temp = new TemporaryFolder();

  private String ledger;

  @Before
  public void setUp() {
    ledger = new File( temp.getRoot(), "ledger.tsv" ).getAbsolutePath();
  }

  private String run( String... args ) throws Exception {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    int exit = new LicenseIssuer( new PrintStream( buffer, true, "UTF-8" ), args ).run();
    assertEquals( 0, exit );
    return new String( buffer.toByteArray(), StandardCharsets.UTF_8 ).trim();
  }

  private String issue() throws Exception {
    return run( "issue", "--private-key", DEV_KEY, "--ledger", ledger, "--customer", "Acme Corp", "--term", "1y" );
  }

  /** A request from a made-up machine: the request carries the fingerprint, the issuer never sees the machine. */
  private static String request( String key, MachineFingerprint machine ) throws Exception {
    return org.pentaho.di.license.ActivationRequestFactory.request( LicenseKey.decode( key ), machine );
  }

  private static MachineFingerprint machine( String key, String id ) throws Exception {
    return org.pentaho.di.license.ActivationRequestFactory.machine( LicenseKey.decode( key ), id );
  }

  @Test
  public void issuingNeedsATerm() throws Exception {
    try {
      new LicenseIssuer( new PrintStream( new ByteArrayOutputStream(), true, "UTF-8" ), new String[] { "issue",
        "--private-key", DEV_KEY, "--ledger", ledger, "--customer", "Acme Corp" } ).run();
      fail( "expected a usage error" );
    } catch ( IllegalArgumentException e ) {
      assertTrue( e.getMessage(), e.getMessage().contains( "--term" ) );
    }
    String perpetual = run( "issue", "--private-key", DEV_KEY, "--ledger", ledger, "--customer", "Acme Corp", "--term",
      "perpetual" );
    assertTrue( LicenseKey.decode( perpetual ).getPayload().isPerpetual() );
  }

  @Test
  public void renewPrintsTheNewKeyAndAnActivationCodePerMachine() throws Exception {
    String key = issue();
    String code = run( "activate", "--private-key", DEV_KEY, "--ledger", ledger, request( key, machine( key, "a" ) ) );
    File dir = new File( temp.getRoot(), "renewal" );
    String out = run( "renew", "--private-key", DEV_KEY, "--ledger", ledger, "--term", "2y", key, "--out",
      dir.getPath() );
    assertTrue( out, out.startsWith( "Renewed license " + LicenseKey.decode( key ).getPayload().getLicenseId() ) );
    LicenseKey renewed = LicenseKey.decode( new String( Files.readAllBytes( new File( dir, "license-key.txt" )
      .toPath() ), StandardCharsets.UTF_8 ).trim() );
    String activationId = Activation.decode( code ).getActivationId().toString();
    Activation reissued = Activation.decode( new String( Files.readAllBytes( new File( dir, activationId
      + ".helixact" ).toPath() ), StandardCharsets.UTF_8 ).trim() );
    assertEquals( renewed.getPayload(), reissued.getLicense() );
    assertEquals( machine( key, "a" ), reissued.getFingerprint() );
  }

  @Test
  public void issueActivateAndRecord() throws Exception {
    String key = issue();
    assertTrue( key.startsWith( LicenseKey.PREFIX ) );
    String code = run( "activate", "--private-key", DEV_KEY, "--ledger", ledger, request( key, machine( key, "a" ) ) );
    Activation activation = Activation.decode( code );
    assertEquals( machine( key, "a" ), activation.getFingerprint() );
    assertEquals( LicenseKey.decode( key ).getPayload(), activation.getLicense() );

    List<String> lines = Files.readAllLines( new File( ledger ).toPath() );
    assertEquals( Ledger.HEADER, lines.get( 0 ) );
    assertTrue( lines.get( 1 ).contains( "\tissued\t" ) );
    assertTrue( lines.get( 2 ).contains( "\tactivated\t" + activation.getLicense().getLicenseId() + "\t"
      + activation.getActivationId() ) );
  }

  @Test
  public void aSecondMachineIsRefusedUntilTheFirstIsReleased() throws Exception {
    String key = issue();
    String first = run( "activate", "--private-key", DEV_KEY, "--ledger", ledger, request( key, machine( key, "a" ) ) );
    try {
      run( "activate", "--private-key", DEV_KEY, "--ledger", ledger, request( key, machine( key, "b" ) ) );
      fail( "a second machine must be refused" );
    } catch ( LicenseException e ) {
      assertTrue( e.getMessage(), e.getMessage().contains( "already activated" ) );
    }
    Activation a = Activation.decode( first );
    String release = new ReleaseCode( a.getLicense().getLicenseId(), a.getActivationId(), 1L ).encode();
    assertTrue( run( "release", "--ledger", ledger, release ).contains( "can be activated again" ) );
    String second = run( "activate", "--private-key", DEV_KEY, "--ledger", ledger, request( key, machine( key, "b" ) ) );
    assertEquals( machine( key, "b" ), Activation.decode( second ).getFingerprint() );
  }

  @Test
  public void theSameMachineCanBeActivatedAgain() throws Exception {
    String key = issue();
    String first = run( "activate", "--private-key", DEV_KEY, "--ledger", ledger, request( key, machine( key, "a" ) ) );
    String again = run( "activate", "--private-key", DEV_KEY, "--ledger", ledger, request( key, machine( key, "a" ) ) );
    assertFalse( Activation.decode( first ).getActivationId().equals( Activation.decode( again ).getActivationId() ) );
    String ledgerText = new String( Files.readAllBytes( new File( ledger ).toPath() ), StandardCharsets.UTF_8 );
    assertTrue( ledgerText.contains( "replaced by " + Activation.decode( again ).getActivationId() ) );
  }

  @Test
  public void anUnboundActivationUsesUpTheLicenseToo() throws Exception {
    String key = issue();
    Activation unbound = Activation.decode( run( "activate", "--private-key", DEV_KEY, "--ledger", ledger, "--unbound",
      key ) );
    assertFalse( unbound.isBound() );
    try {
      run( "activate", "--private-key", DEV_KEY, "--ledger", ledger, request( key, machine( key, "a" ) ) );
      fail( "the license is used up" );
    } catch ( LicenseException e ) {
      assertTrue( e.getMessage(), e.getMessage().contains( "(unbound)" ) );
    }
    run( "release", "--ledger", ledger, "--activation-id", unbound.getActivationId().toString(), "--reason", "test" );
    run( "activate", "--private-key", DEV_KEY, "--ledger", ledger, request( key, machine( key, "a" ) ) );
  }

  @Test
  public void aKeyNotIssuedWithTheEmbeddedKeyIsRefused() throws Exception {
    String key = issue();
    String tampered = LicenseKey.PREFIX + new StringBuilder( key.substring( LicenseKey.PREFIX.length() ) )
      .reverse();
    try {
      run( "activate", "--private-key", DEV_KEY, "--ledger", ledger, "--unbound", tampered );
      fail( "expected a refusal" );
    } catch ( LicenseException e ) {
      // refused
    }
  }

  @Test
  public void issuesAndChangesAnInstanceLimit() throws Exception {
    String key = run( "issue", "--private-key", DEV_KEY, "--ledger", ledger, "--customer", "Acme Corp", "--term", "1y",
      "--instances", "300", "--over-limit", "warn" );
    InstanceLimit limit = LicenseKey.decode( key ).getPayload().getInstanceLimit();
    assertEquals( InstanceLimit.of( 300, InstanceLimit.Counting.FOLDER, true ), limit );

    String declared = run( "issue", "--private-key", DEV_KEY, "--ledger", ledger, "--customer", "Acme Corp", "--term",
      "1y", "--instances", "20", "--counting", "declared" );
    assertEquals( InstanceLimit.of( 20, InstanceLimit.Counting.DECLARED, false ),
      LicenseKey.decode( declared ).getPayload().getInstanceLimit() );

    // --instances alone keeps the counting and the soft limit
    String out = run( "change", "--private-key", DEV_KEY, "--ledger", ledger, "--instances", "500", key );
    assertTrue( out, out.startsWith( "Changed license " ) );
    assertTrue( out, out.contains( "up to 500 at once, counted in a shared folder (over the limit: allowed, with a"
      + " warning), was up to 300" ) );
    assertTrue( out, out.contains( "The license isn't activated anywhere" ) );

    String none = run( "change", "--private-key", DEV_KEY, "--ledger", ledger, "--instances", "none", key );
    assertTrue( none, none.contains( ": no limit, was up to 300" ) );
  }

  @Test
  public void refusesNonsenseLimits() throws Exception {
    String[][] wrong = {
      { "--instances", "0" }, { "--instances", "many" }, { "--instances", "3", "--counting", "server" },
      { "--instances", "3", "--over-limit", "maybe" }, { "--counting", "folder" } };
    for ( String[] options : wrong ) {
      List<String> args = new java.util.ArrayList<>( java.util.Arrays.asList( "issue", "--private-key", DEV_KEY,
        "--ledger", ledger, "--customer", "Acme Corp", "--term", "1y" ) );
      args.addAll( java.util.Arrays.asList( options ) );
      try {
        run( args.toArray( new String[ 0 ] ) );
        fail( "expected a refusal of " + args );
      } catch ( IllegalArgumentException e ) {
        // expected
      }
    }
  }
}
