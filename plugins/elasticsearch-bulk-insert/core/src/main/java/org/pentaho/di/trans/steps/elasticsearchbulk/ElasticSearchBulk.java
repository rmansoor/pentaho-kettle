/*! ******************************************************************************
 *
 * Pentaho Data Integration
 *
 * Copyright (C) 2002-2026 by Hitachi Vantara : http://www.pentaho.com
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

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang.StringUtils;
import org.apache.http.conn.ConnectTimeoutException;
import org.pentaho.di.core.exception.KettleException;
import org.pentaho.di.core.exception.KettleStepException;
import org.pentaho.di.core.row.RowDataUtil;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.core.row.ValueMetaInterface;
import org.pentaho.di.i18n.BaseMessages;
import org.pentaho.di.trans.Trans;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.BaseStep;
import org.pentaho.di.trans.step.StepDataInterface;
import org.pentaho.di.trans.step.StepInterface;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.step.StepMetaInterface;

import java.io.IOException;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Does bulk insert of data into ElasticSearch, through the REST bulk API.
 *
 * @author webdetails
 * @since 16-02-2011
 */
public class ElasticSearchBulk extends BaseStep implements StepInterface {

  private static final String INSERT_ERROR_CODE = null;
  private static Class<?> PKG = ElasticSearchBulkMeta.class; // for i18n

  static final String OP_CREATE = "create";
  static final String OP_INDEX = "index";

  /** Dates as the transport client wrote them: ISO 8601 in UTC with milliseconds. */
  static final DateTimeFormatter DATE_FORMAT =
    DateTimeFormatter.ofPattern( "yyyy-MM-dd'T'HH:mm:ss.SSSX" ).withZone( ZoneOffset.UTC );

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final JsonFactory JSON = MAPPER.getFactory();

  private ElasticSearchBulkMeta meta;
  private ElasticSearchBulkData data;

  private ElasticSearchRestConnection connection;
  private String index;
  private String type;
  /** Whether the bulk actions carry {@code _type}: only for servers before 7.0, which still have mapping types. */
  private boolean sendType;

  private StringBuilder currentRequest;
  private int actionsInRequest;

  private int batchSize = 2;

  private boolean isJsonInsert = false;
  private int jsonFieldIdx = 0;

  private String idOutFieldName = null;
  private Integer idFieldIndex = null;

  private Long timeout = null;
  private TimeUnit timeoutUnit = TimeUnit.MILLISECONDS;

  private int numberOfErrors = 0;

  private boolean stopOnError = true;
  private boolean useOutput = true;

  private Map<String, String> columnsToJson;
  private boolean hasFields;

  private String opType = OP_CREATE;

  public ElasticSearchBulk( StepMeta stepMeta, StepDataInterface stepDataInterface, int copyNr, TransMeta transMeta,
                            Trans trans ) {
    super( stepMeta, stepDataInterface, copyNr, transMeta, trans );
  }

  public boolean processRow( StepMetaInterface smi, StepDataInterface sdi ) throws KettleException {

    Object[] rowData = getRow();
    if ( rowData == null ) {
      if ( currentRequest != null && actionsInRequest > 0 ) {
        // didn't fill a whole batch
        processBatch( false );
      }
      setOutputDone();
      return false;
    }

    if ( first ) {
      first = false;
      setupData();
      detectServerVersion();
      newRequest();
      initFieldIndexes();
    }

    try {
      data.inputRowBuffer[data.nextBufferRowIdx++] = rowData;
      return indexRow( data.inputRowMeta, rowData ) || !stopOnError;
    } catch ( KettleStepException e ) {
      throw e;
    } catch ( Exception e ) {
      rejectAllRows( e.getLocalizedMessage() );
      String msg = BaseMessages.getString( PKG, "ElasticSearchBulk.Log.Exception", e.getLocalizedMessage() );
      logError( msg );
      throw new KettleStepException( msg, e );
    }
  }

  /**
   * Initialize <code>this.data</code>
   *
   * @throws KettleStepException
   */
  private void setupData() throws KettleStepException {
    data.nextBufferRowIdx = 0;
    data.inputRowMeta = getInputRowMeta().clone(); // only available after first getRow();
    data.inputRowBuffer = new Object[batchSize][];
    data.outputRowMeta = data.inputRowMeta.clone();
    meta.getFields( data.outputRowMeta, getStepname(), null, null, this, repository, metaStore );
  }

  /** Mapping types went away in Elasticsearch 7: only older servers get the step's type. */
  private void detectServerVersion() throws KettleStepException {
    int major;
    try {
      major = connection.majorVersion();
    } catch ( IOException e ) {
      String msg = isUnreachable( e )
        ? BaseMessages.getString( PKG, "ElasticSearchBulkDialog.Error.NoNodesFound" ) + ": " + e.getLocalizedMessage()
        : BaseMessages.getString( PKG, "ElasticSearchBulk.Log.Exception", ElasticSearchRestConnection.describe( e ) );
      logError( msg );
      throw new KettleStepException( msg, e );
    }
    sendType = major < 7 && StringUtils.isNotBlank( type );
    if ( major >= 7 && StringUtils.isNotBlank( type ) ) {
      logBasic( "Elasticsearch " + major + " has no mapping types: type '" + type + "' is not sent" );
    }
  }

  private static boolean isUnreachable( IOException e ) {
    return e instanceof ConnectException || e instanceof ConnectTimeoutException
      || e.getCause() instanceof ConnectException;
  }

  private void newRequest() {
    currentRequest = new StringBuilder();
    actionsInRequest = 0;
  }

  private void initFieldIndexes() throws KettleStepException {
    if ( isJsonInsert ) {
      Integer idx = getFieldIdx( data.inputRowMeta, environmentSubstitute( meta.getJsonField() ) );
      if ( idx != null ) {
        jsonFieldIdx = idx.intValue();
      } else {
        throw new KettleStepException( BaseMessages.getString( PKG, "ElasticSearchBulk.Error.NoJsonField" ) );
      }
    }

    idOutFieldName = environmentSubstitute( meta.getIdOutField() );

    if ( StringUtils.isNotBlank( meta.getIdInField() ) ) {
      idFieldIndex = getFieldIdx( data.inputRowMeta, environmentSubstitute( meta.getIdInField() ) );
      if ( idFieldIndex == null ) {
        throw new KettleStepException( BaseMessages.getString( PKG, "ElasticSearchBulk.Error.InvalidIdField" ) );
      }
    } else {
      idFieldIndex = null;
    }
  }

  private static Integer getFieldIdx( RowMetaInterface rowMeta, String fieldName ) {
    if ( fieldName == null ) {
      return null;
    }

    for ( int i = 0; i < rowMeta.size(); i++ ) {
      String name = rowMeta.getValueMeta( i ).getName();
      if ( fieldName.equals( name ) ) {
        return i;
      }
    }
    return null;
  }

  /**
   * @param rowMeta The metadata for the row to be indexed
   * @param row     The data for the row to be indexed
   */

  private boolean indexRow( RowMetaInterface rowMeta, Object[] row ) throws KettleStepException {
    try {

      String id = idFieldIndex != null ? "" + row[idFieldIndex] : null; // "" just in case field isn't string
      String source = isJsonInsert ? sourceFromJsonField( row[jsonFieldIdx] ) : sourceFromRowFields( rowMeta, row );

      currentRequest.append( actionLine( opType, index, sendType ? type : null, id ) ).append( '\n' );
      currentRequest.append( source ).append( '\n' );
      actionsInRequest++;

      if ( actionsInRequest >= batchSize ) {
        return processBatch( true );
      } else {
        return true;
      }

    } catch ( KettleStepException e ) {
      throw e;
    } catch ( Exception e ) {
      throw new KettleStepException( BaseMessages.getString( PKG, "ElasticSearchBulk.Log.Exception", e
              .getLocalizedMessage() ), e );
    }
  }

  /** The bulk action line, e.g. <code>{"create":{"_index":"i","_id":"1"}}</code>. */
  static String actionLine( String opType, String index, String type, String id ) throws IOException {
    StringWriter out = new StringWriter();
    try ( JsonGenerator json = JSON.createGenerator( out ) ) {
      json.writeStartObject();
      json.writeObjectFieldStart( opType );
      json.writeStringField( "_index", index );
      if ( type != null ) {
        json.writeStringField( "_type", type );
      }
      if ( id != null ) {
        json.writeStringField( "_id", id );
      }
      json.writeEndObject();
      json.writeEndObject();
    }
    return out.toString();
  }

  /** A JSON document from the JSON field, on one line as the bulk API needs. */
  static String sourceFromJsonField( Object jsonString ) throws KettleStepException, IOException {
    JsonNode document;
    if ( jsonString instanceof byte[] ) {
      document = MAPPER.readTree( (byte[]) jsonString );
    } else if ( jsonString instanceof String ) {
      document = MAPPER.readTree( (String) jsonString );
    } else {
      throw new KettleStepException( BaseMessages.getString( PKG, "ElasticSearchBulk.Error.NoJsonFieldFormat" ) );
    }
    if ( document == null || !document.isObject() ) {
      throw new KettleStepException( BaseMessages.getString( PKG, "ElasticSearchBulk.Error.NoJsonFieldFormat" ) );
    }
    return MAPPER.writeValueAsString( document );
  }

  private String sourceFromRowFields( RowMetaInterface rowMeta, Object[] row ) throws IOException {
    StringWriter out = new StringWriter();
    try ( JsonGenerator json = JSON.createGenerator( out ) ) {
      json.writeStartObject();
      for ( int i = 0; i < rowMeta.size(); i++ ) {
        if ( idFieldIndex != null && i == idFieldIndex ) { // skip id
          continue;
        }

        ValueMetaInterface valueMeta = rowMeta.getValueMeta( i );
        String name = hasFields ? columnsToJson.get( valueMeta.getName() ) : valueMeta.getName();
        if ( StringUtils.isNotBlank( name ) ) {
          json.writeFieldName( name );
          writeValue( json, row[i] );
        }
      }
      json.writeEndObject();
    }
    return out.toString();
  }

  /** Writes a field value the way the transport client's XContentBuilder did. */
  static void writeValue( JsonGenerator json, Object value ) throws IOException {
    if ( value == null ) {
      json.writeNull();
    } else if ( value instanceof String ) {
      json.writeString( (String) value );
    } else if ( value instanceof Long || value instanceof Integer || value instanceof Short
      || value instanceof Byte ) {
      json.writeNumber( ( (Number) value ).longValue() );
    } else if ( value instanceof Double || value instanceof Float ) {
      json.writeNumber( ( (Number) value ).doubleValue() );
    } else if ( value instanceof BigDecimal ) {
      json.writeNumber( (BigDecimal) value );
    } else if ( value instanceof BigInteger ) {
      json.writeNumber( (BigInteger) value );
    } else if ( value instanceof Boolean ) {
      json.writeBoolean( (Boolean) value );
    } else if ( value instanceof Date ) { // includes Timestamp; millisecond precision, as before
      json.writeString( DATE_FORMAT.format( ( (Date) value ).toInstant() ) );
    } else if ( value instanceof byte[] ) {
      json.writeBinary( (byte[]) value ); // base64
    } else if ( value instanceof InetAddress ) {
      json.writeString( ( (InetAddress) value ).getHostAddress() );
    } else {
      json.writeString( value.toString() );
    }
  }

  public boolean init( StepMetaInterface smi, StepDataInterface sdi ) {
    meta = (ElasticSearchBulkMeta) smi;
    data = (ElasticSearchBulkData) sdi;

    if ( super.init( smi, sdi ) ) {

      try {

        numberOfErrors = 0;

        initFromMeta();
        initClient();

        return true;

      } catch ( Exception e ) {
        logError( BaseMessages.getString( PKG, "ElasticSearchBulk.Log.ErrorOccurredDuringStepInitialize" )
                + e.getMessage() );
      }
    }
    return false;
  }

  private void initFromMeta() {
    index = environmentSubstitute( meta.getIndex() );
    type = environmentSubstitute( meta.getType() );
    batchSize = meta.getBatchSizeInt( this );
    try {
      timeout = Long.parseLong( environmentSubstitute( meta.getTimeOut() ) );
    } catch ( NumberFormatException e ) {
      timeout = null;
    }
    timeoutUnit = meta.getTimeoutUnit();
    isJsonInsert = meta.isJsonInsert();
    useOutput = meta.isUseOutput();
    stopOnError = meta.isStopOnError();

    columnsToJson = meta.getFieldsMap();
    this.hasFields = columnsToJson.size() > 0;

    this.opType =
            StringUtils.isNotBlank( meta.getIdInField() ) && meta.isOverWriteIfSameId() ? OP_INDEX : OP_CREATE;

  }

  private boolean processBatch( boolean makeNew ) throws KettleStepException {

    boolean responseOk = false;

    JsonNode response = null;
    try {
      response = connection.bulk( currentRequest.toString() );
    } catch ( IOException e ) {
      String msg = BaseMessages.getString( PKG, "ElasticSearchBulk.Error.BatchExecuteFail",
        ElasticSearchRestConnection.describe( e ) );
      if ( e instanceof SocketTimeoutException ) {
        msg = BaseMessages.getString( PKG, "ElasticSearchBulk.Error.Timeout" );
      }
      logError( msg );
      rejectAllRows( msg );
    }

    if ( response != null ) {
      responseOk = handleResponse( response );
    } else { // have to assume all failed
      numberOfErrors += actionsInRequest;
      setErrors( numberOfErrors );
    }

    if ( makeNew ) {
      newRequest();
      data.nextBufferRowIdx = 0;
      data.inputRowBuffer = new Object[batchSize][];
    } else {
      currentRequest = null;
      data.inputRowBuffer = null;
    }

    return responseOk;
  }

  /**
   * @param response the bulk API response
   * @return <code>true</code> if no errors
   */
  private boolean handleResponse( JsonNode response ) {

    boolean hasErrors = response.path( "errors" ).asBoolean( false );
    JsonNode items = response.path( "items" );

    if ( hasErrors ) {
      logError( buildFailureMessage( items ) );
    }

    int errorsInBatch = 0;

    if ( hasErrors || useOutput ) {
      for ( int itemId = 0; itemId < items.size(); itemId++ ) {
        JsonNode item = itemResult( items.get( itemId ) );
        String failure = failureMessage( item );
        if ( failure != null ) {
          // log
          logDetailed( failure );
          errorsInBatch++;
          if ( getStepMeta().isDoingErrorHandling() ) {
            rejectRow( itemId, failure );
          }
        } else if ( useOutput ) {
          if ( idOutFieldName != null ) {
            addIdToRow( item.path( "_id" ).asText(), itemId );
          }
          echoRow( itemId );
        }
      }
    }

    numberOfErrors += errorsInBatch;
    setErrors( numberOfErrors );
    int linesOK = actionsInRequest - errorsInBatch;

    if ( useOutput ) {
      setLinesOutput( getLinesOutput() + linesOK );
    } else {
      setLinesWritten( getLinesWritten() + linesOK );
    }

    return !hasErrors;
  }

  /** Each bulk response item is <code>{"create"|"index": {...}}</code>: returns the inner object. */
  static JsonNode itemResult( JsonNode item ) {
    return item != null && item.size() > 0 ? item.elements().next() : MAPPER.createObjectNode();
  }

  /** The failure of one bulk item, or <code>null</code> if it succeeded. */
  static String failureMessage( JsonNode itemResult ) {
    JsonNode error = itemResult.get( "error" );
    if ( error == null || error.isNull() ) {
      int status = itemResult.path( "status" ).asInt( 200 );
      return status >= 300 ? "HTTP status " + status : null;
    }
    if ( error.isTextual() ) {
      return error.asText();
    }
    String type = error.path( "type" ).asText( "" );
    String reason = error.path( "reason" ).asText( "" );
    return type.isEmpty() ? reason : type + ": " + reason;
  }

  static String buildFailureMessage( JsonNode items ) {
    StringBuilder sb = new StringBuilder( "failure in bulk execution:" );
    for ( int i = 0; i < items.size(); i++ ) {
      JsonNode item = itemResult( items.get( i ) );
      String failure = failureMessage( item );
      if ( failure != null ) {
        sb.append( "\n[" ).append( i ).append( "]: index [" ).append( item.path( "_index" ).asText() )
          .append( "], id [" ).append( item.path( "_id" ).asText() ).append( "], message [" ).append( failure )
          .append( ']' );
      }
    }
    return sb.toString();
  }

  private void addIdToRow( String id, int rowIndex ) {

    data.inputRowBuffer[rowIndex] =
            RowDataUtil.resizeArray( data.inputRowBuffer[rowIndex], getInputRowMeta().size() + 1 );
    data.inputRowBuffer[rowIndex][getInputRowMeta().size()] = id;

  }

  /**
   * Send input row to output
   *
   * @param rowIndex
   */
  private void echoRow( int rowIndex ) {
    try {

      putRow( data.outputRowMeta, data.inputRowBuffer[rowIndex] );

    } catch ( KettleStepException e ) {
      logError( e.getLocalizedMessage() );
    } catch ( ArrayIndexOutOfBoundsException e ) {
      logError( e.getLocalizedMessage() );
    }
  }

  /**
   * Send input row to error.
   *
   * @param index
   * @param errorMsg
   */
  private void rejectRow( int index, String errorMsg ) {
    try {

      putError( getInputRowMeta(), data.inputRowBuffer[index], 1, errorMsg, null, INSERT_ERROR_CODE );

    } catch ( KettleStepException e ) {
      logError( e.getLocalizedMessage() );
    } catch ( ArrayIndexOutOfBoundsException e ) {
      logError( e.getLocalizedMessage() );
    }
  }

  private void rejectAllRows( String errorMsg ) {
    for ( int i = 0; i < data.nextBufferRowIdx; i++ ) {
      rejectRow( i, errorMsg );
    }
  }

  private void initClient() {
    Map<String, String> settings = new HashMap<>();
    meta.getSettingsMap().forEach( ( key, value ) -> settings.put( key, environmentSubstitute( value ) ) );

    List<ElasticSearchBulkMeta.Server> servers = new ArrayList<>();
    for ( ElasticSearchBulkMeta.Server server : meta.getServers() ) {
      ElasticSearchBulkMeta.Server resolved = new ElasticSearchBulkMeta.Server();
      resolved.address = environmentSubstitute( server.getAddress() );
      resolved.port = server.getPort();
      servers.add( resolved );
    }

    Long timeoutMillis = timeout != null && timeoutUnit != null ? timeoutUnit.toMillis( timeout ) : null;
    connection = new ElasticSearchRestConnection( servers, settings, timeoutMillis, getLogChannel() );
  }

  private void disposeClient() throws IOException {

    if ( connection != null ) {
      connection.close();
      connection = null;
    }

  }

  public void dispose( StepMetaInterface smi, StepDataInterface sdi ) {
    meta = (ElasticSearchBulkMeta) smi;
    data = (ElasticSearchBulkData) sdi;
    try {
      disposeClient();
    } catch ( Exception e ) {
      logError( e.getLocalizedMessage(), e );
    }
    super.dispose( smi, sdi );
  }
}
