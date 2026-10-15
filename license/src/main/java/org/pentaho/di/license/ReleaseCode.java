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
import java.util.UUID;

/**
 * Printed by {@code license deactivate} after it removes an activation from a machine. The customer sends it to
 * Hitachi Vantara so the license can be activated on another machine.
 * <p>
 * Without a network there is no way to prove the activation is really gone (a copy of the machine could keep
 * it), so this is a record for the issuer's ledger, not cryptographic evidence.
 */
public final class ReleaseCode {

  public static final String PREFIX = "HELIXREL-";
  static final byte FORMAT_VERSION = 1;

  private final UUID licenseId;
  private final UUID activationId;
  private final long releasedAt;

  public ReleaseCode( UUID licenseId, UUID activationId, long releasedAt ) {
    this.licenseId = licenseId;
    this.activationId = activationId;
    this.releasedAt = releasedAt;
  }

  public UUID getLicenseId() {
    return licenseId;
  }

  public UUID getActivationId() {
    return activationId;
  }

  public long getReleasedAt() {
    return releasedAt;
  }

  public String encode() {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try ( DataOutputStream out = new DataOutputStream( buffer ) ) {
      out.writeByte( FORMAT_VERSION );
      out.writeLong( licenseId.getMostSignificantBits() );
      out.writeLong( licenseId.getLeastSignificantBits() );
      out.writeLong( activationId.getMostSignificantBits() );
      out.writeLong( activationId.getLeastSignificantBits() );
      out.writeLong( releasedAt );
    } catch ( IOException e ) {
      throw new UncheckedIOException( e );
    }
    return Codec.toCheckedText( PREFIX, buffer.toByteArray() );
  }

  public static ReleaseCode decode( String text ) throws LicenseException {
    byte[] body = Codec.fromCheckedText( PREFIX, text, "release code" );
    try ( DataInputStream in = new DataInputStream( new ByteArrayInputStream( body ) ) ) {
      int version = in.readUnsignedByte();
      if ( version != FORMAT_VERSION ) {
        throw new LicenseException( "Unsupported release code version: " + version );
      }
      return new ReleaseCode( new UUID( in.readLong(), in.readLong() ), new UUID( in.readLong(), in.readLong() ),
        in.readLong() );
    } catch ( IOException e ) {
      throw new LicenseException( "Malformed release code", e );
    }
  }
}
