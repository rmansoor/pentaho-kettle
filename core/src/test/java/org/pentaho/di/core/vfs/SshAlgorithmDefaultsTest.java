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

package org.pentaho.di.core.vfs;

import com.jcraft.jsch.JSch;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SshAlgorithmDefaultsTest {

  @Test
  public void mergeKeepsExistingOrderAndAppendsOnlyMissing() {
    assertEquals( "rsa-sha2-512,ssh-ed25519,ssh-rsa",
      SshAlgorithmDefaults.merge( "rsa-sha2-512,ssh-ed25519,ssh-rsa", "ssh-rsa" ) );
    assertEquals( "rsa-sha2-512,ssh-rsa,ssh-dss", SshAlgorithmDefaults.merge( "rsa-sha2-512", "ssh-rsa", "ssh-dss" ) );
  }

  @Test
  public void mergeHandlesEmptyCurrentList() {
    assertEquals( "ssh-rsa", SshAlgorithmDefaults.merge( null, "ssh-rsa" ) );
    assertEquals( "ssh-rsa", SshAlgorithmDefaults.merge( "", "ssh-rsa" ) );
  }

  @Test
  public void applyKeepsModernAlgorithmsFirst() {
    String before = JSch.getConfig( "server_host_key" );
    SshAlgorithmDefaults.apply();
    String after = JSch.getConfig( "server_host_key" );
    assertTrue( after.startsWith( before.split( "," )[ 0 ] ) );
    assertTrue( after.contains( "ssh-rsa" ) );
  }
}
