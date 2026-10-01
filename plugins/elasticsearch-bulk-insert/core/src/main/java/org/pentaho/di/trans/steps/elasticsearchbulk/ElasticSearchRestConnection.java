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

package org.pentaho.di.trans.steps.elasticsearchbulk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.TrustAllStrategy;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.message.BasicHeader;
import org.apache.http.nio.entity.NByteArrayEntity;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.pentaho.di.core.logging.LogChannelInterface;
import org.pentaho.di.trans.steps.elasticsearchbulk.ElasticSearchBulkMeta.Server;

import java.io.Closeable;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * HTTP connection to an Elasticsearch cluster, used by the step and by the dialog's test buttons.
 * <p>
 * Talks to the REST API (port 9200) with the low-level REST client, so it works with Elasticsearch 6.x, 7.x and 8.x
 * servers. The step used to connect with the transport client on port 9300; a server entered with port 9300 is sent to
 * port 9200 instead.
 * <p>
 * Settings understood (the Settings tab); any other setting belonged to the transport client and is ignored:
 * <ul>
 *   <li>{@value #SCHEME}: {@code http} (default) or {@code https}. A server address may also start with
 *   {@code https://}.</li>
 *   <li>{@value #USERNAME} and {@value #PASSWORD}: basic authentication. {@value #XPACK_USER} ({@code user:password},
 *   as the x-pack transport client used it) is accepted too.</li>
 *   <li>{@value #API_KEY}: sent as {@code Authorization: ApiKey <value>} (the base64 encoded {@code id:key}).</li>
 *   <li>{@value #PATH_PREFIX}: when Elasticsearch sits behind a proxy under a path.</li>
 *   <li>{@value #SSL_VERIFICATION}: {@code none} trusts any certificate and host name (testing only).</li>
 * </ul>
 */
public class ElasticSearchRestConnection implements Closeable {

  public static final int DEFAULT_HTTP_PORT = 9200;
  public static final int TRANSPORT_PORT = 9300;

  public static final String SCHEME = "http.scheme";
  public static final String USERNAME = "http.username";
  public static final String PASSWORD = "http.password";
  public static final String API_KEY = "http.api_key";
  public static final String PATH_PREFIX = "http.path_prefix";
  public static final String SSL_VERIFICATION = "http.ssl.verification_mode";
  static final String XPACK_USER = "xpack.security.user";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final RestClient client;
  private final LogChannelInterface log;

  /**
   * @param servers       the servers from the step; the address may carry a scheme ({@code https://host})
   * @param settings      the step's settings, variables already substituted
   * @param timeoutMillis connect and socket timeout, or {@code null} for the client defaults
   * @param log           where to report adjusted ports and ignored settings; may be {@code null}
   */
  public ElasticSearchRestConnection( Collection<Server> servers, Map<String, String> settings, Long timeoutMillis,
                                      LogChannelInterface log ) {
    this.log = log;
    String scheme = StringUtils.defaultIfBlank( settings.get( SCHEME ), "http" ).trim().toLowerCase( Locale.ROOT );

    List<HttpHost> hosts = new ArrayList<>();
    for ( Server server : servers ) {
      hosts.add( toHost( server.getAddress(), server.getPort(), scheme ) );
    }
    if ( hosts.isEmpty() ) {
      throw new IllegalArgumentException( "No Elasticsearch server configured" );
    }

    RestClientBuilder builder = RestClient.builder( hosts.toArray( new HttpHost[ 0 ] ) );
    String prefix = settings.get( PATH_PREFIX );
    if ( StringUtils.isNotBlank( prefix ) ) {
      builder.setPathPrefix( prefix.trim() );
    }

    String apiKey = settings.get( API_KEY );
    if ( StringUtils.isNotBlank( apiKey ) ) {
      builder.setDefaultHeaders( new Header[] { new BasicHeader( "Authorization", "ApiKey " + apiKey.trim() ) } );
    }

    CredentialsProvider credentials = credentials( settings );
    boolean trustAll = "none".equalsIgnoreCase( StringUtils.trimToEmpty( settings.get( SSL_VERIFICATION ) ) );
    builder.setHttpClientConfigCallback( http -> {
      if ( credentials != null ) {
        http.setDefaultCredentialsProvider( credentials );
      }
      if ( trustAll ) {
        try {
          http.setSSLContext( new SSLContextBuilder().loadTrustMaterial( null, TrustAllStrategy.INSTANCE ).build() );
          http.setSSLHostnameVerifier( NoopHostnameVerifier.INSTANCE );
        } catch ( Exception e ) {
          throw new IllegalStateException( e );
        }
      }
      return http;
    } );

    if ( timeoutMillis != null && timeoutMillis > 0 ) {
      int millis = (int) Math.min( Integer.MAX_VALUE, timeoutMillis );
      builder.setRequestConfigCallback( rc -> rc.setConnectTimeout( millis ).setSocketTimeout( millis ) );
    }

    for ( String key : settings.keySet() ) {
      if ( !isKnownSetting( key ) && log != null ) {
        log.logBasic( "Setting '" + key + "' applied only to the old transport client and is ignored" );
      }
    }
    client = builder.build();
  }

  static boolean isKnownSetting( String key ) {
    return SCHEME.equals( key ) || USERNAME.equals( key ) || PASSWORD.equals( key ) || API_KEY.equals( key )
      || PATH_PREFIX.equals( key ) || SSL_VERIFICATION.equals( key ) || XPACK_USER.equals( key );
  }

  private static CredentialsProvider credentials( Map<String, String> settings ) {
    String user = settings.get( USERNAME );
    String password = settings.get( PASSWORD );
    String xpack = settings.get( XPACK_USER );
    if ( StringUtils.isBlank( user ) && StringUtils.isNotBlank( xpack ) ) {
      int colon = xpack.indexOf( ':' );
      user = colon < 0 ? xpack : xpack.substring( 0, colon );
      password = colon < 0 ? "" : xpack.substring( colon + 1 );
    }
    if ( StringUtils.isBlank( user ) ) {
      return null;
    }
    BasicCredentialsProvider provider = new BasicCredentialsProvider();
    provider.setCredentials( AuthScope.ANY, new UsernamePasswordCredentials( user, StringUtils.defaultString(
      password ) ) );
    return provider;
  }

  /** Builds the HTTP host for a configured server, moving the transport port (and no port) to the HTTP port. */
  HttpHost toHost( String address, int port, String defaultScheme ) {
    String host = StringUtils.trimToEmpty( address );
    String scheme = defaultScheme;
    int sep = host.indexOf( "://" );
    if ( sep > 0 ) {
      scheme = host.substring( 0, sep ).toLowerCase( Locale.ROOT );
      host = host.substring( sep + 3 );
    }
    host = StringUtils.removeEnd( host, "/" );
    // host:port in the address wins over the port column
    int colon = host.lastIndexOf( ':' );
    if ( colon > 0 && host.indexOf( ':' ) == colon ) {
      try {
        port = Integer.parseInt( host.substring( colon + 1 ) );
        host = host.substring( 0, colon );
      } catch ( NumberFormatException e ) {
        // not a port, keep the address as entered
      }
    }
    if ( port <= 0 ) {
      port = DEFAULT_HTTP_PORT;
    } else if ( port == TRANSPORT_PORT ) {
      if ( log != null ) {
        log.logBasic( "Server " + host + ": port 9300 is the transport port; connecting to the REST port "
          + DEFAULT_HTTP_PORT + " instead" );
      }
      port = DEFAULT_HTTP_PORT;
    }
    return new HttpHost( host, port, scheme );
  }

  /** {@code GET /}: cluster name and version. */
  public JsonNode info() throws IOException {
    return perform( new Request( "GET", "/" ) );
  }

  /** The server's major version, from {@code GET /}. */
  public int majorVersion() throws IOException {
    String number = info().path( "version" ).path( "number" ).asText( "" );
    try {
      return Integer.parseInt( StringUtils.substringBefore( number, "." ) );
    } catch ( NumberFormatException e ) {
      throw new IOException( "Unrecognised Elasticsearch version '" + number + "'" );
    }
  }

  /** {@code GET /_cluster/health}: cluster_name, number_of_nodes, status. */
  public JsonNode clusterHealth() throws IOException {
    return perform( new Request( "GET", "/_cluster/health" ) );
  }

  public boolean indexExists( String index ) throws IOException {
    Response response = client.performRequest( new Request( "HEAD", "/" + encode( index ) ) );
    return response.getStatusLine().getStatusCode() == 200;
  }

  /** {@code GET /{index}/_stats/docs}: the {@code _shards} section has total and successful. */
  public JsonNode indexStats( String index ) throws IOException {
    return perform( new Request( "GET", "/" + encode( index ) + "/_stats/docs" ) );
  }

  /** Sends a bulk request body (newline delimited JSON) and returns the parsed response. */
  public JsonNode bulk( String ndjson ) throws IOException {
    Request request = new Request( "POST", "/_bulk" );
    // UTF-8 without a charset parameter: Elasticsearch 6.x answers 406 to "application/x-ndjson; charset=UTF-8"
    request.setEntity( new NByteArrayEntity( ndjson.getBytes( StandardCharsets.UTF_8 ),
      ContentType.create( "application/x-ndjson" ) ) );
    return perform( request );
  }

  private JsonNode perform( Request request ) throws IOException {
    Response response = client.performRequest( request );
    return MAPPER.readTree( EntityUtils.toString( response.getEntity(), StandardCharsets.UTF_8 ) );
  }

  /** A readable message for a failed request: the Elasticsearch error reason when the server sent one. */
  public static String describe( Exception e ) {
    if ( e instanceof ResponseException ) {
      Response response = ( (ResponseException) e ).getResponse();
      String reason = null;
      try {
        JsonNode body = MAPPER.readTree( EntityUtils.toString( response.getEntity(), StandardCharsets.UTF_8 ) );
        JsonNode error = body.path( "error" );
        reason = error.isTextual() ? error.asText() : error.path( "reason" ).asText( null );
      } catch ( Exception ignored ) {
        // no JSON body
      }
      return "HTTP " + response.getStatusLine().getStatusCode() + " " + response.getStatusLine().getReasonPhrase()
        + ( reason != null ? ": " + reason : "" );
    }
    return e.getLocalizedMessage() != null ? e.getLocalizedMessage() : e.getClass().getSimpleName();
  }

  private static String encode( String index ) throws IOException {
    return URLEncoder.encode( index, StandardCharsets.UTF_8.name() ).replace( "+", "%20" );
  }

  @Override
  public void close() throws IOException {
    client.close();
  }
}
