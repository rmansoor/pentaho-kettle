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
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * The data a license key carries. Kept deliberately minimal for the "does this customer have a
 * purchased license" phase &mdash; no seat count, no machine binding, no entitlements list yet. Those
 * can be added as new fields behind a bumped {@link #FORMAT_VERSION} without breaking older keys.
 */
public final class LicensePayload {

  /** Bump when the wire format changes. A verifier must reject a payload version it doesn't know. */
  public static final byte FORMAT_VERSION = 1;

  private final UUID licenseId;
  private final String customerId;
  private final String edition;
  private final long issuedAt;
  /** Epoch millis, or 0 for a perpetual (non-expiring) license. */
  private final long expiresAt;

  public LicensePayload( UUID licenseId, String customerId, String edition, long issuedAt, long expiresAt ) {
    this.licenseId = Objects.requireNonNull( licenseId, "licenseId" );
    this.customerId = Objects.requireNonNull( customerId, "customerId" );
    this.edition = Objects.requireNonNull( edition, "edition" );
    this.issuedAt = issuedAt;
    this.expiresAt = expiresAt;
  }

  public UUID getLicenseId() {
    return licenseId;
  }

  public String getCustomerId() {
    return customerId;
  }

  public String getEdition() {
    return edition;
  }

  public long getIssuedAt() {
    return issuedAt;
  }

  public long getExpiresAt() {
    return expiresAt;
  }

  public boolean isPerpetual() {
    return expiresAt <= 0;
  }

  /**
   * Serializes to the signed byte form: 1-byte format version, 16-byte license id, length-prefixed
   * customerId and edition (UTF-8), 8-byte issuedAt, 8-byte expiresAt.
   */
  byte[] toBytes() {
    byte[] customerIdBytes = customerId.getBytes( StandardCharsets.UTF_8 );
    byte[] editionBytes = edition.getBytes( StandardCharsets.UTF_8 );
    if ( customerIdBytes.length > Short.MAX_VALUE ) {
      throw new IllegalArgumentException( "customerId is too long to encode" );
    }
    if ( editionBytes.length > 0xFF ) {
      throw new IllegalArgumentException( "edition is too long to encode" );
    }

    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try ( DataOutputStream out = new DataOutputStream( buffer ) ) {
      out.writeByte( FORMAT_VERSION );
      out.writeLong( licenseId.getMostSignificantBits() );
      out.writeLong( licenseId.getLeastSignificantBits() );
      out.writeShort( customerIdBytes.length );
      out.write( customerIdBytes );
      out.writeByte( editionBytes.length );
      out.write( editionBytes );
      out.writeLong( issuedAt );
      out.writeLong( expiresAt );
    } catch ( IOException e ) {
      // writing to an in-memory buffer never actually fails
      throw new UncheckedIOException( e );
    }
    return buffer.toByteArray();
  }

  static LicensePayload fromBytes( byte[] bytes ) throws LicenseException {
    try ( DataInputStream in = new DataInputStream( new java.io.ByteArrayInputStream( bytes ) ) ) {
      int version = in.readUnsignedByte();
      if ( version != FORMAT_VERSION ) {
        throw new LicenseException( "Unsupported license payload version: " + version );
      }
      long msb = in.readLong();
      long lsb = in.readLong();
      UUID licenseId = new UUID( msb, lsb );

      int customerIdLen = in.readUnsignedShort();
      byte[] customerIdBytes = new byte[ customerIdLen ];
      in.readFully( customerIdBytes );
      String customerId = new String( customerIdBytes, StandardCharsets.UTF_8 );

      int editionLen = in.readUnsignedByte();
      byte[] editionBytes = new byte[ editionLen ];
      in.readFully( editionBytes );
      String edition = new String( editionBytes, StandardCharsets.UTF_8 );

      long issuedAt = in.readLong();
      long expiresAt = in.readLong();

      return new LicensePayload( licenseId, customerId, edition, issuedAt, expiresAt );
    } catch ( IOException e ) {
      throw new LicenseException( "Malformed license payload", e );
    }
  }

  @Override
  public String toString() {
    return "LicensePayload{licenseId=" + licenseId + ", customerId=" + customerId + ", edition=" + edition
      + ", issuedAt=" + issuedAt + ", expiresAt=" + ( isPerpetual() ? "perpetual" : expiresAt ) + "}";
  }

  @Override
  public boolean equals( Object o ) {
    if ( this == o ) {
      return true;
    }
    if ( !( o instanceof LicensePayload ) ) {
      return false;
    }
    LicensePayload that = (LicensePayload) o;
    return issuedAt == that.issuedAt && expiresAt == that.expiresAt
      && licenseId.equals( that.licenseId ) && customerId.equals( that.customerId )
      && edition.equals( that.edition );
  }

  @Override
  public int hashCode() {
    return Objects.hash( licenseId, customerId, edition, issuedAt, expiresAt );
  }
}
