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

package org.pentaho.di.license.issuer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The issuer's record of every license key and activation: a tab-separated text file, appended to and never
 * rewritten, so it can be read, grepped and kept in version control or a backup like any other file.
 * <pre>
 * time  event  license_id  activation_id  customer  edition  binding  note
 * </pre>
 * Events: {@code issued} (a license key), {@code activated} (binding = the machine fingerprint, or
 * {@code unbound}), {@code released} (an activation given back: release code, replaced on the same machine, or
 * released by the issuer).
 * <p>
 * A license can have one activation at a time: activations of a license id minus those released.
 */
final class Ledger {

  static final String HEADER = "time\tevent\tlicense_id\tactivation_id\tcustomer\tedition\tbinding\tnote";

  static final class Entry {
    final String time;
    final String event;
    final String licenseId;
    final String activationId;
    final String customer;
    final String edition;
    final String binding;
    final String note;

    Entry( String time, String event, String licenseId, String activationId, String customer, String edition,
           String binding, String note ) {
      this.time = time;
      this.event = event;
      this.licenseId = licenseId;
      this.activationId = activationId;
      this.customer = customer;
      this.edition = edition;
      this.binding = binding;
      this.note = note;
    }

    String line() {
      return String.join( "\t", time, event, licenseId, activationId, clean( customer ), clean( edition ), binding,
        clean( note ) );
    }
  }

  private final Path path;

  Ledger( Path path ) {
    this.path = path;
  }

  Path path() {
    return path;
  }

  List<Entry> entries() throws IOException {
    List<Entry> out = new ArrayList<>();
    if ( !Files.exists( path ) ) {
      return out;
    }
    for ( String line : Files.readAllLines( path, StandardCharsets.UTF_8 ) ) {
      if ( line.isEmpty() || line.startsWith( "time\t" ) || line.startsWith( "#" ) ) {
        continue;
      }
      String[] f = line.split( "\t", -1 );
      if ( f.length < 8 ) {
        throw new IOException( "Malformed ledger line in " + path + ": " + line );
      }
      out.add( new Entry( f[ 0 ], f[ 1 ], f[ 2 ], f[ 3 ], f[ 4 ], f[ 5 ], f[ 6 ], f[ 7 ] ) );
    }
    return out;
  }

  /** The activations of {@code licenseId} not released, oldest first. */
  List<Entry> active( String licenseId ) throws IOException {
    Map<String, Entry> active = new LinkedHashMap<>();
    Set<String> released = new HashSet<>();
    for ( Entry e : entries() ) {
      if ( !e.licenseId.equals( licenseId ) ) {
        continue;
      }
      if ( e.event.equals( "activated" ) ) {
        active.put( e.activationId, e );
      } else if ( e.event.equals( "released" ) ) {
        released.add( e.activationId );
      }
    }
    active.keySet().removeAll( released );
    return new ArrayList<>( active.values() );
  }

  Entry find( String activationId ) throws IOException {
    for ( Entry e : entries() ) {
      if ( e.event.equals( "activated" ) && e.activationId.equals( activationId ) ) {
        return e;
      }
    }
    return null;
  }

  /** Appends entries under an exclusive file lock, so two issuers sharing a ledger don't interleave. */
  void append( Entry... entries ) throws IOException {
    if ( path.getParent() != null ) {
      Files.createDirectories( path.getParent() );
    }
    try ( FileChannel ch = FileChannel.open( path, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
      StandardOpenOption.APPEND ); FileLock lock = ch.lock() ) {
      StringBuilder text = new StringBuilder();
      if ( ch.size() == 0 ) {
        text.append( HEADER ).append( '\n' );
      }
      for ( Entry e : entries ) {
        text.append( e.line() ).append( '\n' );
      }
      ByteBuffer buffer = ByteBuffer.wrap( text.toString().getBytes( StandardCharsets.UTF_8 ) );
      while ( buffer.hasRemaining() ) {
        ch.write( buffer );
      }
      ch.force( true );
    }
  }

  static String now() {
    return Instant.now().toString();
  }

  private static String clean( String s ) {
    return s == null ? "" : s.replaceAll( "[\\t\\r\\n]+", " " );
  }
}
