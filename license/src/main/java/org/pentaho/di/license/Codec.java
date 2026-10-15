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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.zip.CRC32;

/**
 * The text forms shared by every license artifact: a short prefix, then base64url without padding.
 * <ul>
 *   <li>Signed artifacts (entitlement key, activation): {@code [1-byte version][u16 payload length][payload]
 *   [u16 signature length][signature]}, the signature being SHA256withECDSA over the payload.</li>
 *   <li>Unsigned codes a person carries between machines (activation request, release code): the body plus a
 *   CRC32, so a code damaged in transit is reported as such rather than as some odder error.</li>
 * </ul>
 * Whitespace anywhere in a code is ignored: mail clients wrap long lines.
 */
final class Codec {

  static final String SIGNATURE_ALGORITHM = "SHA256withECDSA";

  private Codec() {
  }

  static byte[] sign( byte[] data, PrivateKey privateKey ) throws LicenseException {
    try {
      Signature signer = Signature.getInstance( SIGNATURE_ALGORITHM );
      signer.initSign( privateKey );
      signer.update( data );
      return signer.sign();
    } catch ( GeneralSecurityException e ) {
      throw new LicenseException( "Failed to sign", e );
    }
  }

  static boolean verify( byte[] data, byte[] signature, PublicKey publicKey ) throws LicenseException {
    try {
      Signature verifier = Signature.getInstance( SIGNATURE_ALGORITHM );
      verifier.initVerify( publicKey );
      verifier.update( data );
      return verifier.verify( signature );
    } catch ( java.security.SignatureException e ) {
      return false;   // a garbled signature is simply not a valid one
    } catch ( GeneralSecurityException e ) {
      throw new LicenseException( "Failed to verify signature", e );
    }
  }

  static byte[] envelope( byte version, byte[] payload, byte[] signature ) {
    if ( payload.length > 0xFFFF || signature.length > 0xFFFF ) {
      throw new IllegalStateException( "payload or signature too large to encode" );
    }
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try ( DataOutputStream out = new DataOutputStream( buffer ) ) {
      out.writeByte( version );
      out.writeShort( payload.length );
      out.write( payload );
      out.writeShort( signature.length );
      out.write( signature );
    } catch ( IOException e ) {
      throw new UncheckedIOException( e );
    }
    return buffer.toByteArray();
  }

  /** -> {payload, signature} */
  static byte[][] openEnvelope( byte version, byte[] raw, String what ) throws LicenseException {
    try ( DataInputStream in = new DataInputStream( new ByteArrayInputStream( raw ) ) ) {
      int v = in.readUnsignedByte();
      if ( v != version ) {
        throw new LicenseException( "Unsupported " + what + " format version: " + v );
      }
      byte[] payload = new byte[ in.readUnsignedShort() ];
      in.readFully( payload );
      byte[] signature = new byte[ in.readUnsignedShort() ];
      in.readFully( signature );
      if ( in.available() > 0 ) {
        throw new LicenseException( "Malformed " + what + ": unexpected trailing data" );
      }
      return new byte[][] { payload, signature };
    } catch ( IOException e ) {
      throw new LicenseException( "Malformed " + what, e );
    }
  }

  static String toText( String prefix, byte[] raw ) {
    return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString( raw );
  }

  /**
   * @param prefixRequired false only for the entitlement key, which has always been accepted without its prefix
   */
  static byte[] fromText( String prefix, boolean prefixRequired, String text, String what ) throws LicenseException {
    if ( text == null ) {
      throw new LicenseException( "The " + what + " is missing" );
    }
    String compact = text.replaceAll( "\\s+", "" );
    if ( compact.startsWith( prefix ) ) {
      compact = compact.substring( prefix.length() );
    } else if ( prefixRequired ) {
      throw new LicenseException( "This is not " + article( what ) + " " + what + " (it should start with " + prefix
        + ")" );
    }
    try {
      return Base64.getUrlDecoder().decode( compact );
    } catch ( IllegalArgumentException e ) {
      throw new LicenseException( "The " + what + " is not validly encoded (copied incompletely?)", e );
    }
  }

  static String toCheckedText( String prefix, byte[] body ) {
    CRC32 crc = new CRC32();
    crc.update( body );
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try ( DataOutputStream out = new DataOutputStream( buffer ) ) {
      out.write( body );
      out.writeInt( (int) crc.getValue() );
    } catch ( IOException e ) {
      throw new UncheckedIOException( e );
    }
    return toText( prefix, buffer.toByteArray() );
  }

  static byte[] fromCheckedText( String prefix, String text, String what ) throws LicenseException {
    byte[] raw = fromText( prefix, true, text, what );
    if ( raw.length < 5 ) {
      throw new LicenseException( "The " + what + " is too short (copied incompletely?)" );
    }
    byte[] body = java.util.Arrays.copyOf( raw, raw.length - 4 );
    CRC32 crc = new CRC32();
    crc.update( body );
    int expected = ( ( raw[ raw.length - 4 ] & 0xFF ) << 24 ) | ( ( raw[ raw.length - 3 ] & 0xFF ) << 16 )
      | ( ( raw[ raw.length - 2 ] & 0xFF ) << 8 ) | ( raw[ raw.length - 1 ] & 0xFF );
    if ( (int) crc.getValue() != expected ) {
      throw new LicenseException( "The " + what + " is damaged (a character was changed or lost in transit)" );
    }
    return body;
  }

  private static String article( String noun ) {
    return "aeiou".indexOf( Character.toLowerCase( noun.charAt( 0 ) ) ) >= 0 ? "an" : "a";
  }
}
