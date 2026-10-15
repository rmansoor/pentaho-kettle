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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The customer's two activation steps, shared by Spoon's activation window and {@code license.sh activate}:
 * <ol>
 *   <li>{@link #start(String)}: a license key in, the request code out. The request is kept as pending, so the
 *   customer can close everything while waiting for the answer and continue later.</li>
 *   <li>{@link #complete(String)}: the activation code in; verified for this machine and installed.</li>
 * </ol>
 * Plus {@link #installDropped()}: an activation file ({@code *.helixact}) dropped into the PDI folder or the Kettle
 * folder is installed at the next start, for servers without a desktop.
 */
public final class Activator {

  static final String PENDING_SUFFIX = ".pending";
  static final String DROP_EXTENSION = ".helixact";
  static final String INSTALLED_SUFFIX = ".installed";

  private final LicenseVerifier verifier;

  public Activator() throws LicenseException {
    this( new LicenseVerifier() );
  }

  public Activator( LicenseVerifier verifier ) {
    this.verifier = verifier;
  }

  /**
   * Step 1: checks the license key and returns the activation request for this machine, keeping it as pending.
   *
   * @param keyText the license key, or the path of a file holding it
   */
  public String start( String keyText ) throws LicenseException {
    LicenseKey key = LicenseKey.decode( Texts.codeOrFile( keyText, LicenseKey.PREFIX, "license key" ) );
    LicenseValidationResult result = verifier.verifyEntitlement( key );
    if ( !result.isValid() ) {
      throw new LicenseException( "This license key can't be used: " + result.getMessage() );
    }
    ActivationRequest request = ActivationRequest.forThisMachine( key );
    if ( !request.getFingerprint().isStable() ) {
      throw new LicenseException( "This machine has no stable identity (" + request.getFingerprint().describe()
        + "), as is usual in a container. Ask Hitachi Vantara for a container activation of this license key"
        + " instead, and set HELIX_LICENSE_FILE to it." );
    }
    String code = request.encode();
    write( pendingPath(), code );
    return code;
  }

  /** The request waiting for its answer, if step 1 was done and step 2 not yet. */
  public static Optional<String> pending() {
    try {
      Path path = pendingPath();
      return Files.isRegularFile( path )
        ? Optional.of( new String( Files.readAllBytes( path ), StandardCharsets.UTF_8 ).trim() ) : Optional.empty();
    } catch ( IOException e ) {
      return Optional.empty();
    }
  }

  /** Forget the pending request (start over with another key). */
  public static void cancel() {
    try {
      Files.deleteIfExists( pendingPath() );
    } catch ( IOException e ) {
      // nothing to forget
    }
  }

  /**
   * Step 2: verifies the activation for this machine and installs it.
   *
   * @param activationText the activation code, or the path of a file holding it
   * @return the verification result; installed only when valid
   */
  public LicenseValidationResult complete( String activationText ) throws LicenseException {
    String code = Texts.codeOrFile( activationText, Activation.PREFIX, "activation code" ).replaceAll( "\\s+", "" );
    if ( !code.startsWith( Activation.PREFIX ) ) {
      throw new LicenseException( code.startsWith( LicenseKey.PREFIX )
        ? "That is the license key. Paste the activation code you received from Hitachi Vantara (it starts with "
          + Activation.PREFIX + ")."
        : "That is not an activation code (it should start with " + Activation.PREFIX + ")." );
    }
    LicenseValidationResult result = verifier.verify( code );
    if ( result.isValid() ) {
      LicenseStore.write( code );
      cancel();
    }
    return result;
  }

  /**
   * Installs the first valid activation file found in the PDI folder or the Kettle folder, and renames it to
   * {@code *.helixact.installed}. Files for another machine are left alone.
   *
   * @return the result of the installed activation, if one was installed
   */
  public Optional<LicenseValidationResult> installDropped() {
    for ( Path file : droppedFiles() ) {
      try {
        String code = new String( Files.readAllBytes( file ), StandardCharsets.UTF_8 ).replaceAll( "\\s+", "" );
        LicenseValidationResult result = verifier.verify( code );
        if ( result.isValid() ) {
          LicenseStore.write( code );
          cancel();
          Files.move( file, file.resolveSibling( file.getFileName() + INSTALLED_SUFFIX ),
            StandardCopyOption.REPLACE_EXISTING );
          return Optional.of( result );
        }
      } catch ( IOException | LicenseException e ) {
        // unreadable or not ours: try the next one
      }
    }
    return Optional.empty();
  }

  static List<Path> droppedFiles() {
    List<Path> out = new ArrayList<>();
    for ( Path dir : new Path[] { pdiDirectory(), Paths.get( LicenseStore.kettleDirectory() ) } ) {
      if ( dir == null || !Files.isDirectory( dir ) ) {
        continue;
      }
      try ( DirectoryStream<Path> files = Files.newDirectoryStream( dir, "*" + DROP_EXTENSION ) ) {
        for ( Path f : files ) {
          out.add( f );
        }
      } catch ( IOException e ) {
        // unreadable folder: nothing dropped there
      }
    }
    return out;
  }

  /** The data-integration folder: two levels above this jar ({@code data-integration/lib/kettle-license.jar}). */
  static Path pdiDirectory() {
    try {
      File jar = new File( Activator.class.getProtectionDomain().getCodeSource().getLocation().toURI() );
      File lib = jar.getParentFile();
      return jar.isFile() && lib != null && lib.getName().equals( "lib" ) && lib.getParentFile() != null
        ? lib.getParentFile().toPath() : null;
    } catch ( Exception e ) {
      return null;
    }
  }

  static Path pendingPath() {
    Path license = LicenseStore.getLicenseFilePath();
    return license.resolveSibling( license.getFileName() + PENDING_SUFFIX );
  }

  private static void write( Path path, String text ) throws LicenseException {
    try {
      if ( path.getParent() != null ) {
        Files.createDirectories( path.getParent() );
      }
      Files.write( path, text.getBytes( StandardCharsets.UTF_8 ) );
    } catch ( IOException e ) {
      throw new LicenseException( "Could not write " + path, e );
    }
  }
}
