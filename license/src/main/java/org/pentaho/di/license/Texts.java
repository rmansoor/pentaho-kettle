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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Reading what a person typed or pasted: a code, or the name of a file holding one. */
public final class Texts {

  private Texts() {
  }

  /**
   * @throws LicenseException naming the code's kind when {@code code} is one of the other license codes than the
   *     one expected (e.g. a release code pasted where the license key goes)
   */
  public static void requireKind( String code, String prefix, String what ) throws LicenseException {
    String c = code.trim();
    String[][] kinds = {
      { ActivationRequest.PREFIX, "an activation request" }, { Activation.PREFIX, "an activation code" },
      { ReleaseCode.PREFIX, "a release code" }, { LicenseKey.PREFIX, "a license key" } };
    for ( String[] kind : kinds ) {
      if ( c.startsWith( kind[ 0 ] ) && !kind[ 0 ].equals( prefix ) ) {
        throw new LicenseException( "That is " + kind[ 1 ] + " (" + kind[ 0 ] + "), not " + article( what ) + " " + what
          + ". " + Character.toUpperCase( what.charAt( 0 ) ) + what.substring( 1 ) + "s start with " + prefix + "." );
      }
    }
  }

  private static String article( String noun ) {
    return "aeiou".indexOf( noun.charAt( 0 ) ) >= 0 ? "an" : "a";
  }

  /**
   * @return {@code input} itself when it is a code, else the content of the file it names
   * @throws LicenseException when it names a file that doesn't exist, saying so plainly
   */
  public static String codeOrFile( String input, String prefix, String what ) throws LicenseException {
    if ( input == null || input.trim().isEmpty() ) {
      throw new LicenseException( "Nothing was entered: paste the " + what + ", or give the file holding it." );
    }
    String text = input.trim();
    if ( text.startsWith( "HELIX" ) ) {
      requireKind( text, prefix, what );
      return text;
    }
    String path = text.startsWith( "~" + java.io.File.separator ) || text.startsWith( "~/" )
      ? System.getProperty( "user.home" ) + text.substring( 1 ) : text;
    Path file;
    try {
      file = Paths.get( path );
    } catch ( InvalidPathException e ) {
      return text;
    }
    if ( Files.isRegularFile( file ) ) {
      try {
        String content = new String( Files.readAllBytes( file ), StandardCharsets.UTF_8 ).trim();
        requireKind( content, prefix, what );
        return content;
      } catch ( IOException e ) {
        throw new LicenseException( "Could not read " + file.toAbsolutePath(), e );
      }
    }
    // base64url never contains these: this was meant as a file name
    if ( text.contains( "." ) || text.contains( "/" ) || text.contains( "\\" ) ) {
      throw new LicenseException( "There is no file " + file.toAbsolutePath() + ". Paste the " + what
        + " itself (it starts with " + prefix + "), or give the full path of the file holding it." );
    }
    return text;
  }
}
