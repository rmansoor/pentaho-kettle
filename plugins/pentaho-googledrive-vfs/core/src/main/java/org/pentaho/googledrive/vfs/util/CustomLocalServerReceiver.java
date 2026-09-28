/*!
* Copyright (C) 2017-2026 by Hitachi Vantara : http://www.pentaho.com
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package org.pentaho.googledrive.vfs.util;

import com.google.api.client.extensions.java6.auth.oauth2.VerificationCodeReceiver;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Receives the OAuth redirect from Google on a local port, and shows the bundled success page.
 * <p>
 * Uses the JDK's built-in HTTP server; it used to run on Jetty 6, which google-oauth-client-jetty no longer brings
 * (since 1.31 its own receiver also uses the JDK server).
 */
public class CustomLocalServerReceiver implements VerificationCodeReceiver {

  static final String CONTEXT = "/Callback";
  private static final String PAGES = "success_page";

  private HttpServer server;
  String code;
  String error;
  private int port;
  private final String host;
  private String url;

  public CustomLocalServerReceiver() {
    this( "localhost", -1 );
  }

  CustomLocalServerReceiver( String host, int port ) {
    this.host = host;
    this.port = port;
  }

  public void setUrl( String url ) {
    this.url = url;
  }

  public String getRedirectUri() throws IOException {
    if ( this.port == -1 ) {
      this.port = getUnusedPort();
    }

    this.server = HttpServer.create( new InetSocketAddress( this.host, this.port ), 0 );
    this.server.createContext( CONTEXT, this::handle );
    this.server.start();

    return "http://" + this.host + ":" + this.port + CONTEXT + "/success.html";
  }

  public String waitForCode() throws IOException {
    return this.code;
  }

  public void stop() throws IOException {
    if ( this.server != null ) {
      this.server.stop( 0 );
      this.server = null;
    }
  }

  public String getHost() {
    return this.host;
  }

  public int getPort() {
    return this.port;
  }

  private static int getUnusedPort() throws IOException {
    Socket s = new Socket();
    s.bind( (SocketAddress) null );

    int var1;
    try {
      var1 = s.getLocalPort();
    } finally {
      s.close();
    }
    return var1;
  }

  void handle( HttpExchange exchange ) throws IOException {
    try {
      Map<String, String> params = queryParameters( exchange.getRequestURI().getRawQuery() );
      this.error = params.get( "error" );
      if ( this.code == null ) {
        this.code = params.get( "code" );
      }

      if ( this.url != null && "access_denied".equals( this.error ) ) {
        exchange.getResponseHeaders().set( "Location", this.url );
        exchange.sendResponseHeaders( 302, -1 );
        return;
      }
      servePage( exchange, exchange.getRequestURI().getPath().substring( CONTEXT.length() ) );
    } finally {
      exchange.close();
    }
  }

  /** Serves a file of the success page (the page itself, its image and fonts) from the plugin's resources. */
  private void servePage( HttpExchange exchange, String path ) throws IOException {
    String name = path.isEmpty() || "/".equals( path ) ? "/success.html" : path;
    InputStream in = name.contains( ".." ) ? null : getClass().getClassLoader().getResourceAsStream( PAGES + name );
    if ( in == null ) {
      exchange.sendResponseHeaders( 404, -1 );
      return;
    }
    try ( InputStream page = in ) {
      byte[] content = page.readAllBytes();
      exchange.getResponseHeaders().set( "Content-Type", contentType( name ) );
      exchange.sendResponseHeaders( 200, content.length );
      try ( OutputStream out = exchange.getResponseBody() ) {
        out.write( content );
      }
    }
  }

  static String contentType( String name ) {
    String ext = name.substring( name.lastIndexOf( '.' ) + 1 ).toLowerCase( Locale.ROOT );
    switch ( ext ) {
      case "html":
        return "text/html; charset=UTF-8";
      case "png":
        return "image/png";
      case "svg":
        return "image/svg+xml";
      case "woff":
        return "font/woff";
      case "ttf":
        return "font/ttf";
      case "eot":
        return "application/vnd.ms-fontobject";
      default:
        return "application/octet-stream";
    }
  }

  static Map<String, String> queryParameters( String rawQuery ) {
    Map<String, String> params = new HashMap<>();
    if ( rawQuery == null || rawQuery.isEmpty() ) {
      return params;
    }
    for ( String pair : rawQuery.split( "&" ) ) {
      int eq = pair.indexOf( '=' );
      String key = URLDecoder.decode( eq < 0 ? pair : pair.substring( 0, eq ), StandardCharsets.UTF_8 );
      String value = eq < 0 ? "" : URLDecoder.decode( pair.substring( eq + 1 ), StandardCharsets.UTF_8 );
      params.putIfAbsent( key, value );
    }
    return params;
  }

  public static final class Builder {
    private String host = "localhost";
    private int port = -1;

    public Builder() {
    }

    public CustomLocalServerReceiver build() {
      return new CustomLocalServerReceiver( this.host, this.port );
    }

    public String getHost() {
      return this.host;
    }

    public CustomLocalServerReceiver.Builder setHost( String host ) {
      this.host = host;
      return this;
    }

    public int getPort() {
      return this.port;
    }

    public CustomLocalServerReceiver.Builder setPort( int port ) {
      this.port = port;
      return this;
    }
  }
}
