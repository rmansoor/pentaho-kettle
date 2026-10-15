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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The raw, identifying facts about this machine that a {@link MachineFingerprint} is built from. Everything is
 * read locally, without network access or native code, and is only ever stored or sent hashed.
 * <ul>
 *   <li>machine id: {@code /etc/machine-id} (Linux), {@code IOPlatformUUID} (macOS), {@code MachineGuid}
 *   (Windows); null where none can be read</li>
 *   <li>host name, without its domain</li>
 *   <li>MAC addresses of physical network cards: loopback, virtual, tunnel and container interfaces and
 *   locally administered (randomized) addresses are left out, as they change without the hardware changing</li>
 *   <li>operating system family</li>
 * </ul>
 */
final class MachineInfo {

  private static final Pattern VIRTUAL_NAME = Pattern.compile(
    "^(lo|docker|veth|br-|virbr|vmnet|vboxnet|utun|awdl|llw|bridge|tun|tap|zt|wg|anpi|ap\\d|gif|stf|p2p|vnic|cni"
      + "|flannel|cali|kube|tailscale|ham|ppp)" );
  private static final Pattern VIRTUAL_DISPLAY_NAME = Pattern.compile(
    "virtual|hyper-v|vpn|tap-|tunnel|loopback|pseudo|bluetooth|wan miniport|npcap|vmware|virtualbox",
    Pattern.CASE_INSENSITIVE );
  private static final long COMMAND_TIMEOUT_SECONDS = 5;

  final String osFamily;
  final String machineId;
  final String hostname;
  final List<String> macs;

  MachineInfo( String osFamily, String machineId, String hostname, List<String> macs ) {
    this.osFamily = osFamily;
    this.machineId = blankToNull( machineId );
    this.hostname = normalizeHost( hostname );
    this.macs = Collections.unmodifiableList( new ArrayList<>( new TreeSet<>( macs ) ) );
  }

  static MachineInfo collect() {
    String family = osFamily( System.getProperty( "os.name", "" ) );
    return new MachineInfo( family, readMachineId( family ), readHostname( family ), readMacs() );
  }

  static String osFamily( String osName ) {
    String os = osName.toLowerCase( Locale.ROOT );
    if ( os.startsWith( "windows" ) ) {
      return "windows";
    } else if ( os.startsWith( "mac" ) || os.startsWith( "darwin" ) ) {
      return "mac";
    } else if ( os.startsWith( "linux" ) ) {
      return "linux";
    }
    return os.isEmpty() ? "unknown" : os.split( "\\s+" )[ 0 ];
  }

  private static String readMachineId( String family ) {
    switch ( family ) {
      case "linux":
        for ( String file : new String[] { "/etc/machine-id", "/var/lib/dbus/machine-id" } ) {
          String id = readFile( Paths.get( file ) );
          if ( id != null && !id.equals( "uninitialized" ) && !id.matches( "0+" ) ) {
            return id;
          }
        }
        return null;
      case "mac": {
        Matcher m = Pattern.compile( "\"IOPlatformUUID\"\\s*=\\s*\"([^\"]+)\"" )
          .matcher( run( "ioreg", "-rd1", "-c", "IOPlatformExpertDevice" ) );
        return m.find() ? m.group( 1 ) : null;
      }
      case "windows": {
        Matcher m = Pattern.compile( "MachineGuid\\s+REG_SZ\\s+(\\S+)" )
          .matcher( run( "reg", "query", "HKLM\\SOFTWARE\\Microsoft\\Cryptography", "/v", "MachineGuid" ) );
        return m.find() ? m.group( 1 ) : null;
      }
      default:
        return null;
    }
  }

  private static String readHostname( String family ) {
    // not InetAddress.getLocalHost(): that can wait for a DNS timeout on a machine without network
    if ( family.equals( "windows" ) ) {
      return System.getenv( "COMPUTERNAME" );
    }
    String name = family.equals( "linux" ) ? readFile( Paths.get( "/etc/hostname" ) ) : null;
    return name != null ? name : blankToNull( run( "hostname" ) );
  }

  private static List<String> readMacs() {
    List<String> out = new ArrayList<>();
    try {
      for ( NetworkInterface nic : Collections.list( NetworkInterface.getNetworkInterfaces() ) ) {
        try {
          String name = nic.getName() == null ? "" : nic.getName().toLowerCase( Locale.ROOT );
          String display = nic.getDisplayName() == null ? "" : nic.getDisplayName();
          if ( nic.isLoopback() || nic.isVirtual() || nic.isPointToPoint()
            || VIRTUAL_NAME.matcher( name ).find() || VIRTUAL_DISPLAY_NAME.matcher( display ).find() ) {
            continue;
          }
          String mac = physicalMac( nic.getHardwareAddress() );
          if ( mac != null ) {
            out.add( mac );
          }
        } catch ( SocketException e ) {
          // this card can't be read; the others still count
        }
      }
    } catch ( SocketException | RuntimeException e ) {
      // no network cards readable at all: the fingerprint relies on the other facts
    }
    return out;
  }

  /** -> lowercase hex, or null for a missing, all-zero or locally administered (randomized) address */
  static String physicalMac( byte[] mac ) {
    if ( mac == null || mac.length != 6 || ( mac[ 0 ] & 0x02 ) != 0 ) {
      return null;
    }
    StringBuilder hex = new StringBuilder();
    boolean allZero = true;
    for ( byte b : mac ) {
      allZero &= b == 0;
      hex.append( String.format( "%02x", b & 0xFF ) );
    }
    return allZero ? null : hex.toString();
  }

  private static String readFile( Path path ) {
    try {
      return Files.isReadable( path ) ? blankToNull( new String( Files.readAllBytes( path ), StandardCharsets.UTF_8 ) )
        : null;
    } catch ( IOException e ) {
      return null;
    }
  }

  /** -> the command's output, or "" if it can't be run or doesn't finish in time */
  private static String run( String... command ) {
    try {
      Process p = new ProcessBuilder( command ).redirectErrorStream( true ).start();
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      Thread reader = new Thread( () -> {
        try ( InputStream in = p.getInputStream() ) {
          byte[] buffer = new byte[ 4096 ];
          int n;
          while ( ( n = in.read( buffer ) ) != -1 ) {
            out.write( buffer, 0, n );
          }
        } catch ( IOException e ) {
          // whatever was read so far is used
        }
      } );
      reader.setDaemon( true );
      reader.start();
      if ( !p.waitFor( COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS ) ) {
        p.destroyForcibly();
        return "";
      }
      reader.join( 1000 );
      return p.exitValue() == 0 ? new String( out.toByteArray(), StandardCharsets.UTF_8 ) : "";
    } catch ( IOException e ) {
      return "";
    } catch ( InterruptedException e ) {
      Thread.currentThread().interrupt();
      return "";
    }
  }

  static String normalizeHost( String name ) {
    String host = blankToNull( name );
    if ( host == null ) {
      return null;
    }
    host = host.toLowerCase( Locale.ROOT );
    int dot = host.indexOf( '.' );   // "build-01.example.com" and "build-01.local" are the same machine
    return dot > 0 ? host.substring( 0, dot ) : host;
  }

  private static String blankToNull( String s ) {
    if ( s == null ) {
      return null;
    }
    String t = s.trim();
    return t.isEmpty() ? null : t;
  }
}
