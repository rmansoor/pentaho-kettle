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

import org.pentaho.di.core.Const;

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
 * Reads and writes the installed license key from a plain local file alongside the rest of the Kettle
 * user configuration ({@code Const.getKettleDirectory()}, normally {@code ~/.kettle}, or wherever
 * {@code KETTLE_HOME} points).
 * <p>
 * No encryption: the key is a signed, non-secret artifact by design (see {@link LicenseVerifier}) —
 * forging one requires the private signing key, not read access to this file. The file permissions
 * are still locked to the owner where the platform supports it, mainly to avoid casual copy-paste
 * sharing between local accounts.
 */
public final class LicenseStore {

  static final String FILE_NAME = ".helix-license";

  private LicenseStore() {
  }

  public static Path getLicenseFilePath() {
    return Paths.get( Const.getKettleDirectory(), FILE_NAME );
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

  /** Installs (overwriting any existing) license key. Does not verify it — callers should verify first. */
  public static void write( String keyString ) throws LicenseException {
    Path path = getLicenseFilePath();
    try {
      Files.createDirectories( path.getParent() );
      Files.write( path, keyString.trim().getBytes( StandardCharsets.UTF_8 ) );
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

  private static void lockDownPermissions( Path path ) {
    try {
      if ( path.getFileSystem().supportedFileAttributeViews().contains( "posix" ) ) {
        Files.setPosixFilePermissions( path, EnumSet.of( OWNER_READ, OWNER_WRITE ) );
      }
    } catch ( IOException e ) {
      // best-effort only; not being able to tighten permissions shouldn't block installing the license
    }
  }
}
