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

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpHost;
import org.junit.Test;
import org.pentaho.di.core.exception.KettleStepException;

import java.io.StringWriter;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Collections;
import java.util.Date;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ElasticSearchRestTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  public void actionLineHasTypeAndIdOnlyWhenGiven() throws Exception {
    assertEquals( "{\"create\":{\"_index\":\"i\"}}", ElasticSearchBulk.actionLine( "create", "i", null, null ) );
    assertEquals( "{\"index\":{\"_index\":\"i\",\"_type\":\"t\",\"_id\":\"7\"}}",
      ElasticSearchBulk.actionLine( "index", "i", "t", "7" ) );
  }

  @Test
  public void valuesAreWrittenLikeTheTransportClient() throws Exception {
    assertEquals( "\"2020-01-02T03:04:05.006Z\"", write( new Date( 1577934245006L ) ) );
    assertEquals( "\"2020-01-02T03:04:05.006Z\"", write( new Timestamp( 1577934245006L ) ) );
    assertEquals( "\"AQI=\"", write( new byte[] { 1, 2 } ) );
    assertEquals( "12.50", write( new BigDecimal( "12.50" ) ) );
    assertEquals( "3", write( 3L ) );
    assertEquals( "1.5", write( 1.5d ) );
    assertEquals( "true", write( true ) );
    assertEquals( "null", write( null ) );
  }

  @Test
  public void jsonFieldIsPutOnOneLine() throws Exception {
    assertEquals( "{\"a\":1,\"b\":[1,2]}", ElasticSearchBulk.sourceFromJsonField( "{\n  \"a\": 1,\n  \"b\": [1, 2]\n}" ) );
  }

  @Test( expected = KettleStepException.class )
  public void jsonFieldMustBeAnObject() throws Exception {
    ElasticSearchBulk.sourceFromJsonField( "[1]" );
  }

  @Test
  public void itemFailures() throws Exception {
    JsonNode items = MAPPER.readTree( "["
      + "{\"create\":{\"_index\":\"i\",\"_id\":\"1\",\"status\":201}},"
      + "{\"create\":{\"_index\":\"i\",\"_id\":\"2\",\"status\":409,\"error\":{\"type\":\"version_conflict_engine_exception\","
      + "\"reason\":\"document already exists\"}}}]" );
    assertNull( ElasticSearchBulk.failureMessage( ElasticSearchBulk.itemResult( items.get( 0 ) ) ) );
    assertEquals( "version_conflict_engine_exception: document already exists",
      ElasticSearchBulk.failureMessage( ElasticSearchBulk.itemResult( items.get( 1 ) ) ) );
    assertEquals( "failure in bulk execution:\n[1]: index [i], id [2], message [version_conflict_engine_exception: "
      + "document already exists]", ElasticSearchBulk.buildFailureMessage( items ) );
  }

  @Test
  public void serversMoveToTheRestPort() throws Exception {
    ElasticSearchBulkMeta.Server server = new ElasticSearchBulkMeta.Server();
    server.address = "localhost";
    server.port = 9300;
    try ( ElasticSearchRestConnection c = new ElasticSearchRestConnection( Collections.singletonList( server ),
      Collections.emptyMap(), null, null ) ) {
      assertEquals( new HttpHost( "es", 9200, "http" ), c.toHost( "es", 9300, "http" ) );
      assertEquals( new HttpHost( "es", 9200, "http" ), c.toHost( "es", 0, "http" ) );
      assertEquals( new HttpHost( "es", 9201, "https" ), c.toHost( "https://es:9201/", 9300, "http" ) );
      assertEquals( new HttpHost( "es", 443, "https" ), c.toHost( "es", 443, "https" ) );
    }
  }

  private static String write( Object value ) throws Exception {
    StringWriter out = new StringWriter();
    try ( JsonGenerator json = MAPPER.getFactory().createGenerator( out ) ) {
      ElasticSearchBulk.writeValue( json, value );
    }
    return out.toString();
  }
}
