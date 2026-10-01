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

package org.pentaho.di.trans.steps.propertyinput;

import org.ini4j.Profile;
import org.ini4j.Wini;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * IniFile replaced ini4j's Wini in Property Input: it must read the same sections, options and values (raw and
 * expanded) as Wini did, and must not recurse without bound (CVE-2022-41404).
 */
public class IniFileTest {

  private static final String[] SAME_AS_WINI = {
    // plain sections, both operators, spacing, comments, blank lines
    "[db]\nhost = localhost\nport:5432\n  user=admin  \n; comment\n# other comment\n\n[app]\nname = PDI\n",
    // options before any section go to the global section
    "top = 1\nother: 2\n[s]\nk=v\n",
    // option without operator, empty value, value with operators inside
    "[s]\nflag\nempty =\nurl = jdbc:postgresql://h:5432/db?a=b\nweird = a=b:c\n",
    // escaped operator in the name
    "[s]\nkey\\=part = value\npath\\:x : y\n",
    // repeated option keeps its place, takes the last value; repeated section continues
    "[a]\nx=1\ny=2\nx=3\n[b]\nz=9\n[a]\nw=4\n",
    // references: same section, other section, nested, unresolved, escaped
    "[paths]\nbase = /opt/app\nlib = ${base}/lib\nconf = ${other/root}/conf\nnested = ${lib}/ext\n"
      + "missing = ${nope}/x\nmissingSection = ${none/base}\nescaped = \\${base}\n[other]\nroot = /etc\n",
    // system property and environment references
    "[s]\nhome = ${@prop/java.home}\nuser = ${@env/NO_SUCH_ENV_VAR_4711}\n",
    // unicode and a section name with spaces
    "[section one]\ngreeting = h\u00e9llo w\u00f6rld\n[sect=2]\nk = v\n",
    // inline ; and # are part of the value
    "[s]\na = 1 ; not a comment\nb = x # y\n",
  };

  @Test
  public void readsLikeWini() throws Exception {
    for ( String text : SAME_AS_WINI ) {
      assertSameAsWini( text, StandardCharsets.UTF_8 );
    }
  }

  @Test
  public void honoursTheEncoding() throws Exception {
    assertSameAsWini( "[s]\ngreeting = h\u00e9llo\n", StandardCharsets.ISO_8859_1 );
  }

  @Test
  public void selfReferenceTerminates() throws Exception {
    IniFile ini = load( "[s]\na = ${a}\nb = x${c}\nc = ${b}y\n" );
    // ini4j overflows the stack here; IniFile stops and leaves the rest as written
    assertNotNull( ini.get( "s" ).fetch( "a" ) );
    assertTrue( ini.get( "s" ).fetch( "b" ).startsWith( "x" ) );
  }

  @Test
  public void exponentialGrowthIsBounded() throws Exception {
    StringBuilder text = new StringBuilder( "[s]\nv0 = xxxxxxxxxx\n" );
    for ( int i = 1; i <= 40; i++ ) {
      text.append( "v" ).append( i ).append( " = ${v" ).append( i - 1 ).append( "}${v" ).append( i - 1 )
        .append( "}\n" );
    }
    String value = load( text.toString() ).get( "s" ).fetch( "v40" );
    assertTrue( value.length() <= IniFile.MAX_LENGTH );
  }

  @Test
  public void badSectionLine() throws Exception {
    for ( String text : new String[] { "[unclosed\nk=v\n", "[]\nk=v\n", "[s]\n = v\n" } ) {
      try {
        load( text );
        fail( "expected a parse error for: " + text );
      } catch ( IOException expected ) {
        // like ini4j's InvalidFileFormatException
      }
    }
  }

  @Test
  public void missingSectionAndOption() throws Exception {
    IniFile ini = load( "[s]\nk=v\n" );
    assertNull( ini.get( "nope" ) );
    assertNull( ini.get( "s" ).fetch( "nope" ) );
  }

  private static IniFile load( String text ) throws IOException {
    return IniFile.load( new ByteArrayInputStream( text.getBytes( StandardCharsets.UTF_8 ) ), StandardCharsets.UTF_8 );
  }

  private static void assertSameAsWini( String text, Charset charset ) throws Exception {
    byte[] bytes = text.getBytes( charset );
    Wini wini = new Wini();
    wini.getConfig().setFileEncoding( charset );
    wini.load( new ByteArrayInputStream( bytes ) );
    IniFile ini = IniFile.load( new ByteArrayInputStream( bytes ), charset );

    assertEquals( text, new ArrayList<>( wini.keySet() ), new ArrayList<>( ini.sectionNames() ) );
    for ( String name : wini.keySet() ) {
      Profile.Section expected = wini.get( name );
      IniFile.Section actual = ini.get( name );
      List<String> keys = new ArrayList<>( expected.keySet() );
      assertEquals( text, keys, new ArrayList<>( actual.keySet() ) );
      assertEquals( text, expected.size(), actual.size() );
      assertEquals( text, expected.getName(), actual.getName() );
      for ( String key : keys ) {
        assertEquals( text + " [" + name + "] " + key + " (raw)", expected.get( key ), actual.get( key ) );
        assertEquals( text + " [" + name + "] " + key + " (fetch)", expected.fetch( key ), actual.fetch( key ) );
      }
    }
  }
}
