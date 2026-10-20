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

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Objects;

/**
 * How long a license runs: a number of years, or perpetual. A year is a calendar year in UTC: a one-year license
 * issued on 19 October 2026 at 14:05 expires on 19 October 2027 at 14:05 (a license issued on 29 February expires
 * on 28 February).
 * <p>
 * A term is only how the issuer computes {@link LicensePayload#getExpiresAt()}; the key itself carries the expiry,
 * so licenses issued before terms existed (or for a number of days, as trials) verify the same way.
 */
public final class LicenseTerm {

  /** The longest term that can be issued; anything longer should be perpetual. */
  public static final int MAX_YEARS = 10;

  public static final LicenseTerm PERPETUAL = new LicenseTerm( 0 );

  private final int years;

  private LicenseTerm( int years ) {
    this.years = years;
  }

  /** @throws IllegalArgumentException unless 1 &le; {@code years} &le; {@link #MAX_YEARS} */
  public static LicenseTerm years( int years ) {
    if ( years < 1 || years > MAX_YEARS ) {
      throw new IllegalArgumentException( "A license term is 1 to " + MAX_YEARS + " years, or perpetual (not " + years
        + ")" );
    }
    return new LicenseTerm( years );
  }

  /**
   * Reads a term as people write it: {@code perpetual}, {@code 1y}, {@code 2}, {@code 3 years}.
   *
   * @throws IllegalArgumentException for anything else
   */
  public static LicenseTerm parse( String text ) {
    String t = text == null ? "" : text.trim().toLowerCase( Locale.ROOT );
    if ( t.equals( "perpetual" ) || t.equals( "0" ) ) {
      return PERPETUAL;
    }
    String number = t.replaceFirst( "\\s*(y|yr|yrs|year|years)$", "" );
    try {
      return years( Integer.parseInt( number ) );
    } catch ( NumberFormatException e ) {
      throw new IllegalArgumentException( "Not a license term: '" + text + "'. Use a number of years (1y, 2y, ...)"
        + " or 'perpetual'." );
    }
  }

  public boolean isPerpetual() {
    return years == 0;
  }

  /** The number of years, 0 for perpetual. */
  public int getYears() {
    return years;
  }

  /** The expiry for a license starting at {@code start} (epoch millis): 0 for perpetual. */
  public long expiresAt( long start ) {
    return isPerpetual() ? 0
      : Instant.ofEpochMilli( start ).atZone( ZoneOffset.UTC ).plusYears( years ).toInstant().toEpochMilli();
  }

  /** {@code perpetual} or {@code 3y}: what {@link #parse(String)} reads back. */
  public String code() {
    return isPerpetual() ? "perpetual" : years + "y";
  }

  /** {@code perpetual}, {@code 1 year}, {@code 3 years}. */
  @Override
  public String toString() {
    return isPerpetual() ? "perpetual" : years == 1 ? "1 year" : years + " years";
  }

  @Override
  public boolean equals( Object o ) {
    return o instanceof LicenseTerm && ( (LicenseTerm) o ).years == years;
  }

  @Override
  public int hashCode() {
    return Objects.hashCode( years );
  }
}
