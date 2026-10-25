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
 * The data a license key carries: who it is for, the edition, the term, and optionally an {@link InstanceLimit}
 * (how many PDI processes may run at once). Machine binding is the {@link Activation}'s business.
 * <p>
 * A payload without a limit is written as format version 1, byte for byte as before limits existed, so every key
 * and activation issued then keeps its signature; one with a limit is version 2.
 */
public final class LicensePayload {

  /** The newest format version. A verifier must reject a payload version it doesn't know. */
  public static final byte FORMAT_VERSION = 2;
  /** A payload without an instance limit. */
  static final byte FORMAT_VERSION_NO_LIMIT = 1;

  private static final long DAY_MILLIS = 24L * 60 * 60 * 1000;

  private final UUID licenseId;
  private final String customerId;
  private final String edition;
  private final long issuedAt;
  /** Epoch millis, or 0 for a perpetual (non-expiring) license. */
  private final long expiresAt;
  private final InstanceLimit instanceLimit;

  /** A license without an instance limit. */
  public LicensePayload( UUID licenseId, String customerId, String edition, long issuedAt, long expiresAt ) {
    this( licenseId, customerId, edition, issuedAt, expiresAt, InstanceLimit.NONE );
  }

  public LicensePayload( UUID licenseId, String customerId, String edition, long issuedAt, long expiresAt,
                         InstanceLimit instanceLimit ) {
    this.licenseId = Objects.requireNonNull( licenseId, "licenseId" );
    this.customerId = Objects.requireNonNull( customerId, "customerId" );
    this.edition = Objects.requireNonNull( edition, "edition" );
    this.issuedAt = issuedAt;
    this.expiresAt = expiresAt;
    this.instanceLimit = Objects.requireNonNull( instanceLimit, "instanceLimit" );
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

  /** How many PDI processes may run at once; {@link InstanceLimit#NONE} for licenses without a limit. */
  public InstanceLimit getInstanceLimit() {
    return instanceLimit;
  }

  public boolean isPerpetual() {
    return expiresAt <= 0;
  }

  /**
   * Whole days until the license expires, counting a part of a day as a day (1 on its last day), 0 or less once it
   * has expired, {@link Long#MAX_VALUE} for a perpetual license.
   */
  public long daysLeft( long now ) {
    if ( isPerpetual() ) {
      return Long.MAX_VALUE;
    }
    long left = expiresAt - now;
    return left <= 0 ? left / DAY_MILLIS : ( left + DAY_MILLIS - 1 ) / DAY_MILLIS;
  }

  /** The expiry as a date ({@code 2027-10-19}, UTC), or {@code perpetual}. */
  public String expiryDate() {
    return isPerpetual() ? "perpetual"
      : java.time.Instant.ofEpochMilli( expiresAt ).atZone( java.time.ZoneOffset.UTC ).toLocalDate().toString();
  }

  /**
   * Serializes to the signed byte form: 1-byte format version, 16-byte license id, length-prefixed
   * customerId and edition (UTF-8), 8-byte issuedAt, 8-byte expiresAt; version 2 adds the instance limit:
   * 4-byte maximum, 1-byte counting, 1-byte flags (bit 0: soft limit).
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
      out.writeByte( instanceLimit.isLimited() ? FORMAT_VERSION : FORMAT_VERSION_NO_LIMIT );
      out.writeLong( licenseId.getMostSignificantBits() );
      out.writeLong( licenseId.getLeastSignificantBits() );
      out.writeShort( customerIdBytes.length );
      out.write( customerIdBytes );
      out.writeByte( editionBytes.length );
      out.write( editionBytes );
      out.writeLong( issuedAt );
      out.writeLong( expiresAt );
      if ( instanceLimit.isLimited() ) {
        out.writeInt( instanceLimit.getMax() );
        out.writeByte( instanceLimit.getCounting().code );
        out.writeByte( instanceLimit.isSoft() ? InstanceLimit.SOFT_FLAG : 0 );
      }
    } catch ( IOException e ) {
      // writing to an in-memory buffer never actually fails
      throw new UncheckedIOException( e );
    }
    return buffer.toByteArray();
  }

  static LicensePayload fromBytes( byte[] bytes ) throws LicenseException {
    try ( DataInputStream in = new DataInputStream( new java.io.ByteArrayInputStream( bytes ) ) ) {
      int version = in.readUnsignedByte();
      if ( version != FORMAT_VERSION_NO_LIMIT && version != FORMAT_VERSION ) {
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

      InstanceLimit limit = InstanceLimit.NONE;
      if ( version == FORMAT_VERSION ) {
        int max = in.readInt();
        InstanceLimit.Counting counting = InstanceLimit.Counting.ofCode( in.readUnsignedByte() );
        int flags = in.readUnsignedByte();
        if ( max < 1 ) {
          // a limit of 0 would be written as version 1: this payload wasn't made by the issuer
          throw new LicenseException( "Malformed license payload: instance limit " + max );
        }
        limit = InstanceLimit.of( max, counting, ( flags & InstanceLimit.SOFT_FLAG ) != 0 );
      }
      if ( in.available() > 0 ) {
        throw new LicenseException( "Malformed license payload: unexpected trailing data" );
      }
      return new LicensePayload( licenseId, customerId, edition, issuedAt, expiresAt, limit );
    } catch ( IOException e ) {
      throw new LicenseException( "Malformed license payload", e );
    }
  }

  @Override
  public String toString() {
    return "LicensePayload{licenseId=" + licenseId + ", customerId=" + customerId + ", edition=" + edition
      + ", issuedAt=" + issuedAt + ", expiresAt=" + ( isPerpetual() ? "perpetual" : expiresAt )
      + ( instanceLimit.isLimited() ? ", instances=" + instanceLimit : "" ) + "}";
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
      && edition.equals( that.edition ) && instanceLimit.equals( that.instanceLimit );
  }

  @Override
  public int hashCode() {
    return Objects.hash( licenseId, customerId, edition, issuedAt, expiresAt, instanceLimit );
  }
}
