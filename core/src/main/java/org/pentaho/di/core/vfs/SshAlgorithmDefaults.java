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

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Keeps SFTP working against old servers after the move to the maintained JSch fork.
 * <p>
 * The fork prefers modern algorithms and drops SHA-1 based ones that JSch 0.1.54 offered. Those are appended here at
 * the lowest priority, so current servers still negotiate modern algorithms and old servers can still connect, as
 * before. JSch's configuration is global, so this runs once per JVM.
 */
public final class SshAlgorithmDefaults {

  private static volatile boolean applied;

  private SshAlgorithmDefaults() {
  }

  public static void apply() {
    if ( applied ) {
      return;
    }
    synchronized ( SshAlgorithmDefaults.class ) {
      if ( applied ) {
        return;
      }
      append( "server_host_key", "ssh-rsa", "ssh-dss" );
      append( "PubkeyAcceptedAlgorithms", "ssh-rsa", "ssh-dss" );
      append( "kex", "diffie-hellman-group14-sha1", "diffie-hellman-group-exchange-sha1",
        "diffie-hellman-group1-sha1" );
      append( "cipher.s2c", "aes128-cbc", "aes192-cbc", "aes256-cbc", "3des-cbc" );
      append( "cipher.c2s", "aes128-cbc", "aes192-cbc", "aes256-cbc", "3des-cbc" );
      append( "mac.s2c", "hmac-sha1" );
      append( "mac.c2s", "hmac-sha1" );
      applied = true;
    }
  }

  /** Appends the algorithms JSch doesn't already list, keeping its own order and preference. */
  static String merge( String current, String... legacy ) {
    Set<String> algorithms = new LinkedHashSet<>();
    if ( current != null && !current.isEmpty() ) {
      algorithms.addAll( Arrays.asList( current.split( "," ) ) );
    }
    algorithms.addAll( Arrays.asList( legacy ) );
    return String.join( ",", algorithms );
  }

  private static void append( String key, String... legacy ) {
    JSch.setConfig( key, merge( JSch.getConfig( key ), legacy ) );
  }
}
