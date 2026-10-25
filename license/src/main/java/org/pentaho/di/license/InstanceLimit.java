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

import java.util.Locale;
import java.util.Objects;

/**
 * How many PDI processes may run at once with a license, and how they are counted (see
 * {@code docs/licensing/instance-limits.md}). Part of the signed {@link LicensePayload}: a customer can't raise the
 * number or choose a weaker way of counting.
 * <ul>
 *   <li>{@link Counting#DECLARED}: the number is stated and shown, nothing counts (tier 1).</li>
 *   <li>{@link Counting#FOLDER}: each process holds one of the slots in a folder they share ({@link InstanceSlots},
 *   tier 2).</li>
 *   <li>{@link Counting#SERVER}: leases from a license server in the customer's network (tier 3, not implemented
 *   yet: such a license is refused).</li>
 * </ul>
 * A soft limit lets a process over the limit start, with a warning, where a hard one refuses it.
 */
public final class InstanceLimit {

  public enum Counting {
    DECLARED( 0, "declared, not counted" ),
    FOLDER( 1, "counted in a shared folder" ),
    SERVER( 2, "counted by a license server" );

    final int code;
    private final String description;

    Counting( int code, String description ) {
      this.code = code;
      this.description = description;
    }

    static Counting ofCode( int code ) throws LicenseException {
      for ( Counting c : values() ) {
        if ( c.code == code ) {
          return c;
        }
      }
      throw new LicenseException( "This license counts its PDI processes in a way this version of PDI doesn't"
        + " know (" + code + "): it needs a newer PDI" );
    }

    /** {@code declared}, {@code folder} or {@code server}. */
    public static Counting parse( String text ) {
      String t = text == null ? "" : text.trim().toLowerCase( Locale.ROOT );
      for ( Counting c : values() ) {
        if ( c.name().toLowerCase( Locale.ROOT ).equals( t ) ) {
          return c;
        }
      }
      throw new IllegalArgumentException( "counting is declared, folder or server, not '" + text + "'" );
    }

    @Override
    public String toString() {
      return name().toLowerCase( Locale.ROOT );
    }
  }

  /** No limit: what every license issued before limits existed has. */
  public static final InstanceLimit NONE = new InstanceLimit( 0, Counting.DECLARED, false );

  static final int SOFT_FLAG = 1;

  private final int max;
  private final Counting counting;
  private final boolean soft;

  private InstanceLimit( int max, Counting counting, boolean soft ) {
    this.max = max;
    this.counting = counting;
    this.soft = soft;
  }

  /**
   * @param max  how many PDI processes may run at once, at least 1
   * @param soft true: over the limit, a process starts with a warning; false: it is refused
   */
  public static InstanceLimit of( int max, Counting counting, boolean soft ) {
    if ( max < 1 ) {
      throw new IllegalArgumentException( "an instance limit is at least 1, not " + max );
    }
    return new InstanceLimit( max, Objects.requireNonNull( counting, "counting" ), soft );
  }

  public boolean isLimited() {
    return max > 0;
  }

  /** How many processes may run at once, or 0 for no limit. */
  public int getMax() {
    return max;
  }

  public Counting getCounting() {
    return counting;
  }

  public boolean isSoft() {
    return soft;
  }

  /** {@code up to 300 at once, counted in a shared folder (over the limit: refused)}, or {@code no limit}. */
  public String describe() {
    if ( !isLimited() ) {
      return "no limit";
    }
    return "up to " + max + " at once, " + counting.description + ( counting == Counting.DECLARED ? ""
      : soft ? " (over the limit: allowed, with a warning)" : " (over the limit: refused)" );
  }

  @Override
  public boolean equals( Object o ) {
    if ( !( o instanceof InstanceLimit ) ) {
      return false;
    }
    InstanceLimit that = (InstanceLimit) o;
    return max == that.max && counting == that.counting && soft == that.soft;
  }

  @Override
  public int hashCode() {
    return Objects.hash( max, counting, soft );
  }

  @Override
  public String toString() {
    return describe();
  }
}
