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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads INI files for the Property Input step the way it read them with ini4j's {@code Wini}, which is replaced
 * because of CVE-2022-41404 (unbounded recursion when expanding {@code ${...}} references).
 * <ul>
 *   <li>Lines are trimmed; empty lines and lines starting with {@code ;} or {@code #} are skipped.</li>
 *   <li>{@code [name]} starts a section; a section that appears again continues the first one. Options before the
 *   first section belong to the global section {@value #GLOBAL_SECTION}.</li>
 *   <li>An option is split at the first {@code =} or {@code :} not preceded by a backslash; name and value are
 *   trimmed. A line without either is an option with no value. A repeated option keeps its place and takes the last
 *   value. No escapes, line continuations or includes are processed.</li>
 *   <li>{@link Section#fetch(String)} expands {@code ${option}}, {@code ${section/option}}, {@code ${@env/NAME}} and
 *   {@code ${@prop/NAME}}; references that don't resolve are left as written. Unlike ini4j, expansion stops at
 *   {@value #MAX_DEPTH} nested references, {@value #MAX_EXPANSIONS} lookups or {@value #MAX_LENGTH} characters, so
 *   a self-referencing or exponentially growing file cannot exhaust the stack or memory.</li>
 * </ul>
 */
public class IniFile {

  public static final String GLOBAL_SECTION = "?";

  static final int MAX_DEPTH = 32;
  static final int MAX_EXPANSIONS = 1000;
  static final int MAX_LENGTH = 1_000_000;

  // ${option}, ${section/option}, with optional [index] parts as ini4j accepts them; not after a backslash
  private static final Pattern EXPRESSION =
    Pattern.compile( "(?<!\\\\)\\$\\{(([^\\[\\}]+)(\\[([0-9]+)\\])?/)?([^\\[^/\\}]+)(\\[(([0-9]+))\\])?\\}" );
  private static final int G_SECTION = 2;
  private static final int G_SECTION_IDX = 4;
  private static final int G_OPTION = 5;
  private static final int G_OPTION_IDX = 7;

  private final Map<String, Section> sections = new LinkedHashMap<>();

  /** Reads an INI file; the stream is not closed. */
  public static IniFile load( InputStream in, Charset charset ) throws IOException {
    IniFile ini = new IniFile();
    BufferedReader reader =
      new BufferedReader( new InputStreamReader( in, charset == null ? StandardCharsets.UTF_8 : charset ) );
    Section current = null;
    int lineNumber = 0;
    for ( String line = reader.readLine(); line != null; line = reader.readLine() ) {
      lineNumber++;
      line = line.trim();
      if ( line.isEmpty() || line.charAt( 0 ) == ';' || line.charAt( 0 ) == '#' ) {
        continue;
      }
      if ( line.charAt( 0 ) == '[' ) {
        String name = line.length() > 1 && line.charAt( line.length() - 1 ) == ']'
          ? line.substring( 1, line.length() - 1 ).trim() : "";
        if ( name.isEmpty() ) {
          throw new IOException( "Invalid INI section on line " + lineNumber + ": " + line );
        }
        current = ini.section( name );
        continue;
      }
      if ( current == null ) {
        current = ini.section( GLOBAL_SECTION );
      }
      int op = indexOfOperator( line );
      String name = op < 0 ? line : line.substring( 0, op ).trim();
      String value = op < 0 ? null : line.substring( op + 1 ).trim();
      if ( name.isEmpty() ) {
        throw new IOException( "Invalid INI option on line " + lineNumber + ": " + line );
      }
      current.options.put( name, value );
    }
    return ini;
  }

  private Section section( String name ) {
    return sections.computeIfAbsent( name, n -> new Section( this, n ) );
  }

  /** The first '=' or ':' that isn't escaped with a backslash, or -1. */
  static int indexOfOperator( String line ) {
    for ( int i = 0; i < line.length(); i++ ) {
      char c = line.charAt( i );
      if ( ( c == '=' || c == ':' ) && ( i == 0 || line.charAt( i - 1 ) != '\\' ) ) {
        return i;
      }
    }
    return -1;
  }

  /** Section names in file order. */
  public Set<String> sectionNames() {
    return sections.keySet();
  }

  public Section get( String name ) {
    return sections.get( name );
  }

  public void clear() {
    sections.clear();
  }

  public static class Section {
    private final IniFile ini;
    private final String name;
    private final Map<String, String> options = new LinkedHashMap<>();

    Section( IniFile ini, String name ) {
      this.ini = ini;
      this.name = name;
    }

    public String getName() {
      return name;
    }

    /** Option names in file order. */
    public Set<String> keySet() {
      return options.keySet();
    }

    public int size() {
      return options.size();
    }

    public void clear() {
      options.clear();
    }

    /** The value as written, or null. */
    public String get( String option ) {
      return options.get( option );
    }

    /** The value with its {@code ${...}} references expanded, or null. */
    public String fetch( String option ) {
      return fetch( option, new int[] { 0 }, 0 );
    }

    private String fetch( String option, int[] expansions, int depth ) {
      String value = options.get( option );
      if ( value == null || depth >= MAX_DEPTH ) {
        return value;
      }
      StringBuilder buffer = new StringBuilder( value );
      Matcher m = EXPRESSION.matcher( buffer );
      int from = 0;
      while ( from <= buffer.length() && m.find( from ) ) {
        // every lookup counts, resolved or not: bounds the work even for exponentially branching references
        if ( ++expansions[0] > MAX_EXPANSIONS ) {
          break; // leave the rest as written
        }
        String resolved = resolve( m, expansions, depth );
        if ( resolved == null || buffer.length() - ( m.end() - m.start() ) + resolved.length() > MAX_LENGTH ) {
          from = m.end(); // leave it as written
          continue;
        }
        buffer.replace( m.start(), m.end(), resolved );
        m = EXPRESSION.matcher( buffer );
        from = 0;
      }
      return buffer.toString();
    }

    private String resolve( Matcher m, int[] expansions, int depth ) {
      String sectionName = m.group( G_SECTION );
      String optionName = m.group( G_OPTION );
      // ini4j keeps one section per name and one value per option: only index 0 exists
      if ( isNonZero( m.group( G_SECTION_IDX ) ) || isNonZero( m.group( G_OPTION_IDX ) ) ) {
        return null;
      }
      if ( "@env".equals( sectionName ) ) {
        return System.getenv( optionName );
      }
      if ( "@prop".equals( sectionName ) ) {
        return System.getProperty( optionName );
      }
      Section section = sectionName == null ? this : ini.get( sectionName );
      return section == null ? null : section.fetch( optionName, expansions, depth + 1 );
    }

    private static boolean isNonZero( String index ) {
      return index != null && Integer.parseInt( index ) != 0;
    }
  }
}
