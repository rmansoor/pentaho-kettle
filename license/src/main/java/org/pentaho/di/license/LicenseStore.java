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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.Optional;

import static java.nio.file.attribute.PosixFilePermission.OWNER_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;

/**
 * Reads and writes the installed license from a plain local file, by default alongside the rest of the Kettle
 * user configuration ({@code ~/.kettle}, or {@code $KETTLE_HOME/.kettle}). {@value #FILE_PROPERTY} (system
 * property) or {@value #FILE_ENV} (environment) point elsewhere, e.g. one license file for a service account.
 * <p>
 * No encryption: an activation is a signed, non-secret artifact by design (see {@link LicenseVerifier}) &mdash;
 * forging one requires the private signing key, not read access to this file. The file permissions are still
 * locked to the owner where the platform supports it.
 * <p>
 * Next to the license, {@code .helix-license.seen} keeps the latest time a time-limited license was checked at, so
 * that setting the clock back to before it is noticed.
 */
public final class LicenseStore {

  static final String FILE_NAME = ".helix-license";
  static final String SEEN_SUFFIX = ".seen";
  public static final String FILE_PROPERTY = "helix.license.file";
  public static final String FILE_ENV = "HELIX_LICENSE_FILE";

  private LicenseStore() {
  }

  public static Path getLicenseFilePath() {
    String override = firstNonBlank( System.getProperty( FILE_PROPERTY ), System.getenv( FILE_ENV ) );
    return override != null ? Paths.get( override ) : Paths.get( kettleDirectory(), FILE_NAME );
  }

  /** Same as Kettle's Const.getKettleDirectory(), without depending on kettle-core. */
  static String kettleDirectory() {
    String home = firstNonBlank( System.getenv( "KETTLE_HOME" ), System.getProperty( "KETTLE_HOME" ) );
    return ( home != null ? home : System.getProperty( "user.home" ) ) + java.io.File.separator + ".kettle";
  }

  public static Optional<String> read() throws LicenseException {
    Path path = getLicenseFilePath();
    if ( !Files.exists( path ) ) {
      return Optional.empty();
    }
    try {
      String content = new String( Files.readAllBytes( path ), StandardCharsets.UTF_8 ).trim();
      return content.isEmpty() ? Optional.empty() : Optional.of( content );
    } catch ( IOException e ) {
      throw new LicenseException( "Could not read license file: " + path, e );
    }
  }

  /** Installs (overwriting any existing) license. Does not verify it — callers should verify first. */
  public static void write( String license ) throws LicenseException {
    Path path = getLicenseFilePath();
    try {
      if ( path.getParent() != null ) {
        Files.createDirectories( path.getParent() );
      }
      Files.write( path, license.trim().getBytes( StandardCharsets.UTF_8 ) );
      lockDownPermissions( path );
    } catch ( IOException e ) {
      throw new LicenseException( "Could not write license file: " + path, e );
    }
  }

  public static void remove() throws LicenseException {
    Path path = getLicenseFilePath();
    try {
      Files.deleteIfExists( path );
    } catch ( IOException e ) {
      throw new LicenseException( "Could not remove license file: " + path, e );
    }
  }

  /** The latest check time recorded, or 0. */
  static long lastSeen() {
    try {
      Path seen = seenPath();
      return Files.exists( seen )
        ? Long.parseLong( new String( Files.readAllBytes( seen ), StandardCharsets.US_ASCII ).trim() ) : 0;
    } catch ( IOException | NumberFormatException e ) {
      return 0;
    }
  }

  /** Best effort: a read-only location (e.g. a mounted license in a container) just doesn't record. */
  static void recordSeen( long time ) {
    try {
      Path seen = seenPath();
      Files.write( seen, Long.toString( time ).getBytes( StandardCharsets.US_ASCII ) );
      lockDownPermissions( seen );
    } catch ( IOException | RuntimeException e ) {
      // not recording only weakens the clock check; it never blocks a valid license
    }
  }

  private static Path seenPath() {
    Path license = getLicenseFilePath();
    return license.resolveSibling( license.getFileName() + SEEN_SUFFIX );
  }

  private static void lockDownPermissions( Path path ) {
    try {
      if ( path.getFileSystem().supportedFileAttributeViews().contains( "posix" ) ) {
        Files.setPosixFilePermissions( path, EnumSet.of( OWNER_READ, OWNER_WRITE ) );
      }
    } catch ( IOException | UnsupportedOperationException e ) {
      // best-effort only; not being able to tighten permissions shouldn't block installing the license
    }
  }

  static String firstNonBlank( String... values ) {
    for ( String v : values ) {
      if ( v != null && !v.trim().isEmpty() ) {
        return v.trim();
      }
    }
    return null;
  }
}
