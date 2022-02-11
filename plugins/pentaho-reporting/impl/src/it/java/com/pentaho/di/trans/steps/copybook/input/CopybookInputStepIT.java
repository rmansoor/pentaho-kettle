/*!
 * HITACHI VANTARA PROPRIETARY AND CONFIDENTIAL
 *
 * Copyright 2019 Hitachi Vantara. All rights reserved.
 *
 * NOTICE: All information including source code contained herein is, and
 * remains the sole property of Hitachi Vantara and its licensors. The intellectual
 * and technical concepts contained herein are proprietary and confidential
 * to, and are trade secrets of Hitachi Vantara and may be covered by U.S. and foreign
 * patents, or patents in process, and are protected by trade secret and
 * copyright laws. The receipt or possession of this source code and/or related
 * information does not convey or imply any rights to reproduce, disclose or
 * distribute its contents, or to manufacture, use, or sell anything that it
 * may describe, in whole or in part. Any reproduction, modification, distribution,
 * or public display of this information without the express written authorization
 * from Hitachi Vantara is strictly prohibited and in violation of applicable laws and
 * international treaties. Access to the source code contained herein is strictly
 * prohibited to anyone except those individuals and entities who have executed
 * confidentiality and non-disclosure agreements or other agreements with Hitachi Vantara,
 * explicitly covering such access.
 */
package com.pentaho.di.trans.steps.copybook.input;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.pentaho.di.trans.steps.copybook.def.CopybookDefinitionData;
import com.pentaho.di.trans.steps.copybook.def.CopybookDefinitionData.FieldInfo;
import com.pentaho.di.trans.steps.copybook.def.CopybookDialectEnum;
import com.pentaho.di.trans.steps.copybook.def.CopybookLineStructureEnum;
import com.pentaho.di.trans.steps.copybook.meta.CopybookMetaStepMeta;
import com.pentaho.di.trans.steps.copybook.util.CopybookTestUtilities;
import com.pentaho.di.trans.steps.copybook.util.ConversionException;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;
import org.pentaho.di.core.KettleClientEnvironment;
import org.pentaho.di.core.Props;
import org.pentaho.di.core.annotations.Step;
import org.pentaho.di.core.exception.KettleException;
import org.pentaho.di.core.exception.KettleFileException;
import org.pentaho.di.core.exception.KettleStepException;
import org.pentaho.di.core.logging.KettleLogStore;
import org.pentaho.di.core.logging.LogChannelInterface;
import org.pentaho.di.core.logging.LogChannelInterfaceFactory;
import org.pentaho.di.core.logging.LoggingObjectInterface;
import org.pentaho.di.core.plugins.PluginRegistry;
import org.pentaho.di.core.plugins.StepPluginType;
import org.pentaho.di.core.row.RowMeta;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.core.row.value.ValueMetaBinary;
import org.pentaho.di.core.row.value.ValueMetaString;
import org.pentaho.di.core.variables.Variables;
import org.pentaho.di.core.vfs.KettleVFS;
import org.pentaho.di.trans.RowProducer;
import org.pentaho.di.trans.Trans;
import org.pentaho.di.trans.TransHopMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.RowListener;
import org.pentaho.di.trans.step.StepErrorMeta;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.steps.dummytrans.DummyTransMeta;
import org.pentaho.di.trans.steps.injector.InjectorMeta;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;


@RunWith( MockitoJUnitRunner.class )
public class CopybookInputStepIT {

  @Mock
  LogChannelInterfaceFactory logChannelFactory;

  @Mock
  LogChannelInterface logChannel;

  private static final String COPYBOOK_STEP_NAME = "Copybook Input";
  private static final String DUMMY_STEP_NAME = "Dummy Step";
  private static final String ERROR_STEP_NAME = "Dummy Error Step";
  private static final String INJECTOR_STEP_NAME = "Injector";

  @Before
  public void setup() {

    KettleLogStore.setLogChannelInterfaceFactory( logChannelFactory );
    when( logChannelFactory.create( any() ) ).thenReturn( logChannel );
    when( logChannelFactory.create( any(), (LoggingObjectInterface) any( LogChannelInterface.class ) ) )
        .thenReturn( logChannel );
  }

  @BeforeClass
  public static void setupBefore() throws KettleException, IOException {

    KettleClientEnvironment.init();
    PluginRegistry.addPluginType( StepPluginType.getInstance() );
    PluginRegistry.init();

    if ( !Props.isInitialized() ) {
      Props.init( 0 );
    }

    StepPluginType.getInstance().handlePluginAnnotation( CopybookInputStepMeta.class,
      CopybookMetaStepMeta.class.getAnnotation( Step.class ), Collections.<String> emptyList(), false, null );

    CopybookTestUtilities.createTestFiles();

  }

  @AfterClass
  public static void cleanupAfter() throws IOException, KettleFileException {
    CopybookTestUtilities.deleteTestFiles();
  }

  public CopybookInputStepMeta createBasicCopybookInputMeta( String defFile, boolean useLegacy, boolean addNullCheck ) throws Exception {

    CopybookInputStepMeta meta = new CopybookInputStepMeta();
    meta.setDefault();
    meta.getDefinitionMeta().setSourceArchitecture( CopybookDialectEnum.BIG_ENDIAN_MAINFRAME.toString() );
    meta.getDefinitionMeta().setLineStructure( CopybookLineStructureEnum.STANDARD_COLS_6_TO_72.toString() );
    meta.getDefinitionMeta().setCharsetName( CopybookDialectEnum.BIG_ENDIAN_MAINFRAME.getPreferredCharset() );
    meta.getDefinitionMeta().setDefinitionFile( defFile );
    meta.getDefinitionMeta().setUseLegacyPathNaming( useLegacy );
    meta.setStreamConvertErrors( true );

    List<CopybookInputFieldMeta> fields = new ArrayList<>();

    // Very important to create the variables like this so it matches KettleVFS()
    Variables vars = new Variables();
    vars.initializeVariablesFrom( null );

    // Load all the fields from the definition
    CopybookDefinitionData data = new CopybookDefinitionData( meta.getDefinitionMeta(), vars, null, false );
    for ( FieldInfo info : data.getFieldInfo() ) {
      CopybookInputFieldMeta fieldMeta = new CopybookInputFieldMeta( info );
      if ( addNullCheck ) {
        fieldMeta.setFieldNullHexByteValues( "40" );
      }
      fields.add( fieldMeta );
    }
    meta.setFieldList( fields );

    return meta;
  }

  public Trans createTrans( boolean addInjector, boolean addError, CopybookInputStepMeta meta ) throws KettleException {
    TransMeta transMeta = new TransMeta();
    transMeta.setName( "Test Copybook Input" );

    StepMeta stepMeta =
        new StepMeta( PluginRegistry.getInstance().getPluginId( StepPluginType.class, meta ), COPYBOOK_STEP_NAME,
            meta );
    transMeta.addStep( stepMeta );

    DummyTransMeta dMeta = new DummyTransMeta();
    StepMeta dStepMeta =
        new StepMeta( PluginRegistry.getInstance().getPluginId( StepPluginType.class, dMeta ), DUMMY_STEP_NAME, dMeta );
    transMeta.addStep( dStepMeta );

    transMeta.addTransHop( new TransHopMeta( stepMeta, dStepMeta ) );

    if ( addInjector ) {

      InjectorMeta injectorMeta = new InjectorMeta();
      StepMeta iMeta = new StepMeta( PluginRegistry.getInstance().getPluginId( StepPluginType.class, injectorMeta ),
          INJECTOR_STEP_NAME, injectorMeta );
      transMeta.addStep( iMeta );

      transMeta.addTransHop( new TransHopMeta( iMeta, stepMeta ) );

    }

    if ( addError ) {
      DummyTransMeta dErrorMeta = new DummyTransMeta();
      StepMeta dErrorStepMeta = new StepMeta( PluginRegistry.getInstance().getPluginId( StepPluginType.class, dErrorMeta ),
          ERROR_STEP_NAME, dErrorMeta );
      transMeta.addStep( dErrorStepMeta );

      StepErrorMeta stepErrorMeta = new StepErrorMeta( transMeta, stepMeta, dErrorStepMeta );
      stepErrorMeta.setEnabled( true );
      stepErrorMeta.setErrorDescriptionsValuename( "error_description" );
      stepErrorMeta.setNrErrorsValuename( "error_nr" );
      stepMeta.setStepErrorMeta( stepErrorMeta );

      transMeta.addTransHop( new TransHopMeta( stepMeta, dErrorStepMeta ) );
    }

    Trans trans = new Trans( transMeta );
    trans.prepareExecution( null );

    return trans;

  }

  public void createRowListner( final String stepName, final Trans trans, final List<Object[]> outputRows,
      final List<Object[]> outputErrors ) {
    trans.getStepInterface( stepName, 0 ).addRowListener( new RowListener() {

      @Override
      public void rowWrittenEvent( RowMetaInterface rmi, Object[] row ) throws KettleStepException {
        if ( outputRows != null ) {
          outputRows.add( row );
        }
      }

      @Override
      public void rowReadEvent( RowMetaInterface rmi, Object[] row ) throws KettleStepException {
        // Not used.
      }

      @Override
      public void errorRowWrittenEvent( RowMetaInterface rmi, Object[] row ) throws KettleStepException {
        if ( outputErrors != null ) {
          outputErrors.add( row );
        }
      }

    } );
  }

  @Test
  public void testWithoutFieldSource() throws Exception {

    CopybookInputStepMeta meta = createBasicCopybookInputMeta( CopybookTestUtilities.DEFINITION_FILE, true, true );
    meta.setCopybookDataFile( CopybookTestUtilities.DATA_FILE );

    Trans trans = createTrans( false, false, meta );

    final List<Object[]> outputRows = new ArrayList<>();

    createRowListner( COPYBOOK_STEP_NAME, trans, outputRows, null );

    trans.startThreads();
    trans.waitUntilFinished();

    List<Object[]> goldenValues = Arrays.asList( CopybookTestUtilities.getRow1Values(), new Object[5] );

    CopybookTestUtilities.compareToGolden( goldenValues, outputRows );
  }

  @Test
  public void testWithFieldSource() throws Exception {

    String fileNameField = "file_name";

    CopybookInputStepMeta meta = createBasicCopybookInputMeta( CopybookTestUtilities.DEFINITION_FILE, true, true );
    meta.setAcceptFilesFromField( true );
    meta.setAcceptFilesFieldName( fileNameField );

    RowMetaInterface rmi = new RowMeta();
    rmi.addValueMeta( new ValueMetaString( fileNameField ) );

    Trans trans = createTrans( true, false, meta );

    final List<Object[]> outputRows = new ArrayList<>();
    createRowListner( COPYBOOK_STEP_NAME, trans, outputRows, null );

    RowProducer producer = trans.addRowProducer( INJECTOR_STEP_NAME, 0 );

    trans.startThreads();

    producer.putRow( rmi, new Object[] {
      CopybookTestUtilities.DATA_FILE } );
    producer.putRow( rmi, new Object[] {
      CopybookTestUtilities.DATA_FILE } );
    producer.finished();

    trans.waitUntilFinished();

    Object[] copyBookResults = CopybookTestUtilities.getRow1Values();

    Object[] rowWithFileAndResults = new Object[1 + copyBookResults.length];
    rowWithFileAndResults[0] = CopybookTestUtilities.DATA_FILE;
    System.arraycopy( copyBookResults, 0, rowWithFileAndResults, 1, copyBookResults.length );
    Object[] rowWithFile = new Object[1 + copyBookResults.length];
    rowWithFile[0] = CopybookTestUtilities.DATA_FILE;

    List<Object[]> goldenValues =
        Arrays.asList( rowWithFileAndResults, rowWithFile, rowWithFileAndResults, rowWithFile );

    CopybookTestUtilities.compareToGolden( goldenValues, outputRows );
  }

  @Test
  public void testWithBinaryFieldSource() throws Exception {

    String binaryNameField = "record_bytes";

    CopybookInputStepMeta meta = createBasicCopybookInputMeta( CopybookTestUtilities.DEFINITION_FILE, true, true );
    meta.setAcceptRecordBytesFromField( true );
    meta.setInputRecordBytesFieldName( binaryNameField );

    RowMetaInterface rmi = new RowMeta();
    rmi.addValueMeta( new ValueMetaBinary( binaryNameField ) );

    Trans trans = createTrans( true, false, meta );

    final List<Object[]> outputRows = new ArrayList<>();
    createRowListner( COPYBOOK_STEP_NAME, trans, outputRows, null );

    RowProducer producer = trans.addRowProducer( INJECTOR_STEP_NAME, 0 );

    trans.startThreads();

    Variables vars = new Variables();
    vars.initializeVariablesFrom( null );

    List<byte[]> inputRows = new ArrayList<>( 2 );

    try ( InputStream is = KettleVFS.getInputStream( CopybookTestUtilities.DATA_FILE, vars ) ) {

      byte[] rowBuffer = new byte[CopybookTestUtilities.ROW_BYTE_LENGTH];
      while ( is.read( rowBuffer ) == CopybookTestUtilities.ROW_BYTE_LENGTH ) {
        byte[] arrayCopy = Arrays.copyOf( rowBuffer, rowBuffer.length );
        inputRows.add( arrayCopy );
        producer.putRow( rmi, new Object[] {
          arrayCopy } );
      }

    }

    producer.finished();

    trans.waitUntilFinished();

    Object[] copyBookResults = CopybookTestUtilities.getRow1Values();

    Object[] rowWithFileAndResults = new Object[1 + copyBookResults.length];
    rowWithFileAndResults[0] = inputRows.get( 0 );
    System.arraycopy( copyBookResults, 0, rowWithFileAndResults, 1, copyBookResults.length );

    Object[] rowWithFile = new Object[1 + copyBookResults.length];
    rowWithFile[0] = inputRows.get( 1 );

    List<Object[]> goldenValues =
        Arrays.asList( rowWithFileAndResults, rowWithFile );

    CopybookTestUtilities.compareToGolden( goldenValues, outputRows );

  }

  @Test
  public void testWithJSONErrors() throws Exception {

    // Test executes a transformation using streamError option in normal mode which produces JSON Array
    // containing the error content.
    String defPath = getClass().getClassLoader().getResource( "error-examples/with_error.cbl" ).getPath();
    String dataPath = getClass().getClassLoader().getResource( "error-examples/with_error.bin" ).getPath();

    CopybookInputStepMeta meta = createBasicCopybookInputMeta( defPath, false, false );
    meta.setCopybookDataFile( dataPath );

    Trans trans = createTrans( false, true, meta );

    final List<Object[]> copybookRows = new ArrayList<>();
    final List<Object[]> errorRows = new ArrayList<>();
    final List<Object[]> dummyRows = new ArrayList<>();

    createRowListner( COPYBOOK_STEP_NAME, trans, copybookRows, errorRows );
    createRowListner( ERROR_STEP_NAME, trans, dummyRows, null );

    trans.startThreads();
    trans.waitUntilFinished();

    Assert.assertEquals( 5, dummyRows.size() );
    JSONParser parser = new JSONParser();

    // Verify the First row
    Object[] o = dummyRows.get( 0 );

    // Assert that Each row contains 2 errors.
    Assert.assertEquals( 2, ( (Long) o[0]).intValue() );
    String data = (String) o[1];

    JSONArray arry = (JSONArray) parser.parse( data );
    Assert.assertEquals( 2, arry.size() );

    int errCount = 0;
    for ( Object obj : arry ) {
      JSONObject jobj = (JSONObject) obj;

      // Inspect one of the objects for expected result
      if ( ( (String) jobj.get( "fieldName" ) ).equalsIgnoreCase( "LAST-NAME-E" ) ) {
        errCount += 1;
        Assert.assertTrue( ( (String) jobj.get( ConversionException.EXCEPTION_FIELD ) ).equalsIgnoreCase( "NumberFormatException" ) );
        Assert.assertTrue( ( (String) jobj.get( ConversionException.CONVERTER_FIELD ) ).equalsIgnoreCase( "BigNumberColumnConverter" ) );
        Assert.assertEquals( ( (Long) jobj.get( ConversionException.LENGTH_FIELD ) ).intValue(), 5 );
        Assert.assertEquals( ( (Long) jobj.get( ConversionException.POSITION_FIELD ) ).intValue(), 17 );
        Assert.assertTrue( ( (String) jobj.get( ConversionException.VALUE_FIELD ) ).equalsIgnoreCase( "01999f4040" ) );
        Assert.assertTrue( ( (String) jobj.get( ConversionException.MESSAGE_FIELD ) ).equalsIgnoreCase( "" ) );
      }

      if ( ( (String) jobj.get( "fieldName" ) ).equalsIgnoreCase( "OPEN-YEAR" ) ) {
        errCount += 1;
      }
    }
    // Assert that the 2 expected errors were found in the array
    Assert.assertEquals( errCount, 2 );
  }

  @Test
  public void testWithLegacyErrors() throws Exception {
    // Test executes a transformation using streamError option in legacy mode which produces string delimited errors.
    String defPath = getClass().getClassLoader().getResource( "error-examples/with_error.cbl" ).getPath();
    String dataPath = getClass().getClassLoader().getResource( "error-examples/with_error.bin" ).getPath();

    CopybookInputStepMeta meta = createBasicCopybookInputMeta( defPath, true, false );
    meta.setCopybookDataFile( dataPath );

    Trans trans = createTrans( false, true, meta );

    final List<Object[]> copybookRows = new ArrayList<>();
    final List<Object[]> errorRows = new ArrayList<>();
    final List<Object[]> dummyRows = new ArrayList<>();

    createRowListner( COPYBOOK_STEP_NAME, trans, copybookRows, errorRows );
    createRowListner( ERROR_STEP_NAME, trans, dummyRows, null );

    trans.startThreads();
    trans.waitUntilFinished();

    // Legacy has one error per row.
    Assert.assertEquals( 10, dummyRows.size() );
    // Verify the First row
    Object[] o = dummyRows.get(0);

    // Assert that Each row contains 1 errors and contains expected value
    Assert.assertEquals( 1, ( (Long) o[0]).intValue() );
    Assert.assertTrue( ( (String) o[1] ).equalsIgnoreCase( "BigNumberColumnConverter,LAST-NAME-E,01999f4040" ) );

    o = dummyRows.get(1);
    Assert.assertTrue( ( (String) o[1] ).equalsIgnoreCase( "BigNumberColumnConverter,OPEN-YEAR,404040" ) );
  }
}
