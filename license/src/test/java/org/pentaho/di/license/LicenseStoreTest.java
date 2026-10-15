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

import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LicenseStoreTest {

  @Rule
  public TemporaryFolder tempFolder = new TemporaryFolder();

  private String originalKettleHome;

  @Before
  public void redirectKettleHomeToATempDir() {
    originalKettleHome = System.getProperty( "KETTLE_HOME" );
    System.setProperty( "KETTLE_HOME", tempFolder.getRoot().getAbsolutePath() );
  }

  @After
  public void restoreKettleHome() {
    if ( originalKettleHome == null ) {
      System.clearProperty( "KETTLE_HOME" );
    } else {
      System.setProperty( "KETTLE_HOME", originalKettleHome );
    }
  }

  @Test
  public void readReturnsEmptyWhenNothingIsInstalled() throws Exception {
    assertFalse( LicenseStore.read().isPresent() );
  }

  @Test
  public void writeThenReadRoundTrips() throws Exception {
    LicenseStore.write( LicenseKey.PREFIX + "some-key-body" );

    Optional<String> read = LicenseStore.read();

    assertTrue( read.isPresent() );
    assertEquals( LicenseKey.PREFIX + "some-key-body", read.get() );
  }

  @Test
  public void writeTrimsWhitespace() throws Exception {
    LicenseStore.write( "  " + LicenseKey.PREFIX + "abc  \n" );

    assertEquals( LicenseKey.PREFIX + "abc", LicenseStore.read().get() );
  }

  @Test
  public void writeOverwritesAPreviouslyInstalledKey() throws Exception {
    LicenseStore.write( LicenseKey.PREFIX + "first" );
    LicenseStore.write( LicenseKey.PREFIX + "second" );

    assertEquals( LicenseKey.PREFIX + "second", LicenseStore.read().get() );
  }

  @Test
  public void removeDeletesTheInstalledKey() throws Exception {
    LicenseStore.write( LicenseKey.PREFIX + "abc" );

    LicenseStore.remove();

    assertFalse( LicenseStore.read().isPresent() );
  }

  @Test
  public void removeWhenNothingIsInstalledDoesNotThrow() throws Exception {
    LicenseStore.remove(); // just needs to not throw
  }
}
