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

import org.junit.Test;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class LicenseTermTest {

  private static long utc( int year, int month, int day ) {
    return ZonedDateTime.of( year, month, day, 14, 5, 0, 0, ZoneOffset.UTC ).toInstant().toEpochMilli();
  }

  @Test
  public void aYearIsACalendarYear() {
    assertEquals( utc( 2027, 10, 19 ), LicenseTerm.years( 1 ).expiresAt( utc( 2026, 10, 19 ) ) );
    assertEquals( utc( 2029, 10, 19 ), LicenseTerm.years( 3 ).expiresAt( utc( 2026, 10, 19 ) ) );
    // across a leap day: still the same date
    assertEquals( utc( 2028, 3, 1 ), LicenseTerm.years( 1 ).expiresAt( utc( 2027, 3, 1 ) ) );
    // from a leap day: the last day of February
    assertEquals( utc( 2029, 2, 28 ), LicenseTerm.years( 1 ).expiresAt( utc( 2028, 2, 29 ) ) );
  }

  @Test
  public void perpetualNeverExpires() {
    assertEquals( 0, LicenseTerm.PERPETUAL.expiresAt( utc( 2026, 10, 19 ) ) );
    assertTrue( LicenseTerm.PERPETUAL.isPerpetual() );
  }

  @Test
  public void parsesWhatPeopleWrite() {
    assertEquals( LicenseTerm.years( 1 ), LicenseTerm.parse( "1y" ) );
    assertEquals( LicenseTerm.years( 2 ), LicenseTerm.parse( "2" ) );
    assertEquals( LicenseTerm.years( 3 ), LicenseTerm.parse( " 3 Years " ) );
    assertEquals( LicenseTerm.years( 1 ), LicenseTerm.parse( "1 year" ) );
    assertEquals( LicenseTerm.PERPETUAL, LicenseTerm.parse( "Perpetual" ) );
    for ( LicenseTerm t : new LicenseTerm[] { LicenseTerm.PERPETUAL, LicenseTerm.years( 5 ) } ) {
      assertEquals( t, LicenseTerm.parse( t.code() ) );
    }
    assertEquals( "1 year", LicenseTerm.years( 1 ).toString() );
    assertEquals( "2 years", LicenseTerm.years( 2 ).toString() );
  }

  @Test
  public void refusesOtherTerms() {
    for ( String bad : new String[] { "", "11y", "-1", "1.5", "forever", "6 months", null } ) {
      try {
        LicenseTerm.parse( bad );
        fail( "accepted " + bad );
      } catch ( IllegalArgumentException e ) {
        // refused
      }
    }
  }

  @Test
  public void daysLeftCountsAPartOfADayAsADay() {
    long now = utc( 2026, 10, 19 );
    long hour = 60L * 60 * 1000;
    LicensePayload p = new LicensePayload( java.util.UUID.randomUUID(), "c", "ce", now, now + 24 * hour * 10 );
    assertEquals( 10, p.daysLeft( now ) );
    assertEquals( 10, p.daysLeft( now + hour ) );
    assertEquals( 1, p.daysLeft( now + 24 * hour * 10 - hour ) );
    assertTrue( p.daysLeft( now + 24 * hour * 11 ) <= 0 );
    assertEquals( "2026-10-29", p.expiryDate() );
    assertEquals( Long.MAX_VALUE, new LicensePayload( java.util.UUID.randomUUID(), "c", "ce", now, 0 ).daysLeft( now ) );
  }
}
