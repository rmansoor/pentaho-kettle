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

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * A machine's identity as an activation records it: each {@link MachineInfo} fact hashed (SHA-256, truncated to
 * 16 bytes) together with the license id, so a fingerprint reveals no hardware ids and two licenses on the same
 * machine produce unrelated fingerprints.
 * <p>
 * {@link #mismatch(MachineFingerprint)} decides whether the machine PDI runs on is the activated one. It tolerates
 * one routine change (a replaced network card, or a renamed host) but not a different machine:
 * <ul>
 *   <li>the operating system family must match;</li>
 *   <li>if the activation recorded a machine id: the same machine id, plus a shared network card or the same host
 *   name (a machine id alone could come from a cloned disk image);</li>
 *   <li>without a machine id: a shared network card and the same host name.</li>
 * </ul>
 */
public final class MachineFingerprint {

  static final byte FORMAT_VERSION = 1;
  static final int HASH_LENGTH = 16;
  static final int MAX_MACS = 16;

  private static final int HAS_MACHINE_ID = 1;
  private static final int HAS_HOSTNAME = 2;

  private final byte[] osFamily;
  private final byte[] machineId;   // null when the machine has none we can read
  private final byte[] hostname;    // null likewise
  private final List<byte[]> macs;  // sorted

  private MachineFingerprint( byte[] osFamily, byte[] machineId, byte[] hostname, List<byte[]> macs ) {
    this.osFamily = osFamily;
    this.machineId = machineId;
    this.hostname = hostname;
    List<byte[]> sorted = new ArrayList<>( macs );
    sorted.sort( MachineFingerprint::compare );
    this.macs = Collections.unmodifiableList( sorted.size() > MAX_MACS ? sorted.subList( 0, MAX_MACS ) : sorted );
  }

  /** This machine's fingerprint for license {@code licenseId}. */
  public static MachineFingerprint ofThisMachine( UUID licenseId ) {
    return of( licenseId, MachineInfo.collect() );
  }

  static MachineFingerprint of( UUID licenseId, MachineInfo info ) {
    List<byte[]> macs = new ArrayList<>();
    for ( String mac : info.macs ) {
      macs.add( hash( licenseId, "mac", mac ) );
    }
    return new MachineFingerprint( hash( licenseId, "os", info.osFamily ),
      info.machineId == null ? null : hash( licenseId, "machine-id", info.machineId.toLowerCase( java.util.Locale.ROOT ) ),
      info.hostname == null ? null : hash( licenseId, "host", info.hostname ), macs );
  }

  /** @return null if {@code current} is the machine this fingerprint was taken on, else the reason it isn't */
  public String mismatch( MachineFingerprint current ) {
    if ( !Arrays.equals( osFamily, current.osFamily ) ) {
      return "a different operating system";
    }
    boolean sameHost = hostname != null && Arrays.equals( hostname, current.hostname );
    boolean sharedNic = false;
    for ( byte[] mac : macs ) {
      for ( byte[] other : current.macs ) {
        sharedNic |= Arrays.equals( mac, other );
      }
    }
    if ( machineId != null ) {
      if ( !Arrays.equals( machineId, current.machineId ) ) {
        return "a different machine id";
      }
      return sameHost || sharedNic ? null : "the same machine id, but neither the host name nor any network card match";
    }
    if ( sameHost && sharedNic ) {
      return null;
    }
    return !sameHost && !sharedNic ? "a different host name and network cards"
      : !sameHost ? "a different host name" : "different network cards";
  }

  public boolean matches( MachineFingerprint current ) {
    return mismatch( current ) == null;
  }

  /**
   * Whether an activation bound to this fingerprint can match later: it needs a machine id plus a host name or a
   * network card, or both of those. A container typically has neither a machine id nor a hardware network card,
   * and needs an unbound activation instead.
   */
  public boolean isStable() {
    return machineId != null ? hostname != null || !macs.isEmpty() : hostname != null && !macs.isEmpty();
  }

  /** Which facts this fingerprint holds, for status output (never the values). */
  public String describe() {
    return "os, " + ( machineId != null ? "machine id, " : "no machine id, " )
      + ( hostname != null ? "host name, " : "no host name, " ) + macs.size() + " network card(s)";
  }

  /** A text form, for records kept outside an activation (the issuer's ledger). */
  public String encode() {
    java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
    try ( DataOutputStream out = new DataOutputStream( buffer ) ) {
      write( out );
    } catch ( IOException e ) {
      throw new java.io.UncheckedIOException( e );
    }
    return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString( buffer.toByteArray() );
  }

  public static MachineFingerprint decode( String text ) throws LicenseException {
    try ( DataInputStream in = new DataInputStream( new java.io.ByteArrayInputStream(
      java.util.Base64.getUrlDecoder().decode( text.trim() ) ) ) ) {
      return read( in );
    } catch ( IOException | IllegalArgumentException e ) {
      throw new LicenseException( "Malformed machine fingerprint", e );
    }
  }

  void write( DataOutputStream out ) throws IOException {
    out.writeByte( FORMAT_VERSION );
    out.writeByte( ( machineId != null ? HAS_MACHINE_ID : 0 ) | ( hostname != null ? HAS_HOSTNAME : 0 ) );
    out.write( osFamily );
    if ( machineId != null ) {
      out.write( machineId );
    }
    if ( hostname != null ) {
      out.write( hostname );
    }
    out.writeByte( macs.size() );
    for ( byte[] mac : macs ) {
      out.write( mac );
    }
  }

  static MachineFingerprint read( DataInputStream in ) throws IOException, LicenseException {
    int version = in.readUnsignedByte();
    if ( version != FORMAT_VERSION ) {
      throw new LicenseException( "Unsupported machine fingerprint version: " + version );
    }
    int flags = in.readUnsignedByte();
    byte[] os = readHash( in );
    byte[] machine = ( flags & HAS_MACHINE_ID ) != 0 ? readHash( in ) : null;
    byte[] host = ( flags & HAS_HOSTNAME ) != 0 ? readHash( in ) : null;
    int n = in.readUnsignedByte();
    if ( n > MAX_MACS ) {
      throw new LicenseException( "Malformed machine fingerprint" );
    }
    List<byte[]> macs = new ArrayList<>();
    for ( int i = 0; i < n; i++ ) {
      macs.add( readHash( in ) );
    }
    return new MachineFingerprint( os, machine, host, macs );
  }

  private static byte[] readHash( DataInputStream in ) throws IOException {
    byte[] h = new byte[ HASH_LENGTH ];
    in.readFully( h );
    return h;
  }

  static byte[] hash( UUID licenseId, String kind, String value ) {
    try {
      MessageDigest sha = MessageDigest.getInstance( "SHA-256" );
      sha.update( "pdi-fingerprint-1".getBytes( StandardCharsets.UTF_8 ) );
      sha.update( licenseId.toString().getBytes( StandardCharsets.UTF_8 ) );
      sha.update( (byte) 0 );
      sha.update( kind.getBytes( StandardCharsets.UTF_8 ) );
      sha.update( (byte) 0 );
      sha.update( value.getBytes( StandardCharsets.UTF_8 ) );
      return Arrays.copyOf( sha.digest(), HASH_LENGTH );
    } catch ( NoSuchAlgorithmException e ) {
      throw new IllegalStateException( "SHA-256 is part of every Java runtime", e );
    }
  }

  private static int compare( byte[] a, byte[] b ) {
    for ( int i = 0; i < Math.min( a.length, b.length ); i++ ) {
      int c = Integer.compare( a[ i ] & 0xFF, b[ i ] & 0xFF );
      if ( c != 0 ) {
        return c;
      }
    }
    return Integer.compare( a.length, b.length );
  }

  @Override
  public boolean equals( Object o ) {
    if ( !( o instanceof MachineFingerprint ) ) {
      return false;
    }
    MachineFingerprint that = (MachineFingerprint) o;
    if ( !Arrays.equals( osFamily, that.osFamily ) || !Arrays.equals( machineId, that.machineId )
      || !Arrays.equals( hostname, that.hostname ) || macs.size() != that.macs.size() ) {
      return false;
    }
    for ( int i = 0; i < macs.size(); i++ ) {
      if ( !Arrays.equals( macs.get( i ), that.macs.get( i ) ) ) {
        return false;
      }
    }
    return true;
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode( osFamily ) * 31 + Arrays.hashCode( machineId );
  }

  @Override
  public String toString() {
    return "MachineFingerprint{" + describe() + "}";
  }
}
