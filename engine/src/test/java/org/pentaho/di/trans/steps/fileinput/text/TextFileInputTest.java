/*! ******************************************************************************
 *
 * Pentaho Data Integration
 *
 * Copyright (C) 2002-2019 by Hitachi Vantara : http://www.pentaho.com
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

package org.pentaho.di.trans.steps.fileinput.text;

import org.apache.commons.io.IOUtils;
import org.apache.commons.vfs2.FileContent;
import org.apache.commons.vfs2.FileObject;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.mockito.Mockito;
import org.pentaho.di.core.KettleEnvironment;
import org.pentaho.di.core.RowSet;
import org.pentaho.di.core.SingleRowRowSet;
import org.pentaho.di.core.exception.KettleFileException;
import org.pentaho.di.core.fileinput.FileInputList;
import org.pentaho.di.core.logging.LogChannelInterface;
import org.pentaho.di.core.logging.LoggingObjectInterface;
import org.pentaho.di.core.playlist.FilePlayListAll;
import org.pentaho.di.core.row.RowMeta;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.core.row.value.ValueMetaString;
import org.pentaho.di.core.util.Assert;
import org.pentaho.di.core.variables.Variables;
import org.pentaho.di.core.vfs.KettleVFS;
import org.pentaho.di.junit.rules.RestorePDIEngineEnvironment;
import org.pentaho.di.trans.Trans;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.TransTestingUtil;
import org.pentaho.di.trans.step.StepDataInterface;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.step.errorhandling.AbstractFileErrorHandler;
import org.pentaho.di.trans.step.errorhandling.FileErrorHandler;
import org.pentaho.di.trans.steps.StepMockUtil;
import org.pentaho.di.trans.steps.calculator.CalculatorData;
import org.pentaho.di.trans.steps.calculator.CalculatorMeta;
import org.pentaho.di.trans.steps.file.BaseFileField;
import org.pentaho.di.trans.steps.file.IBaseFileInputReader;
import org.pentaho.di.trans.steps.file.IBaseFileInputStepControl;
import org.pentaho.di.trans.steps.mock.StepMockHelper;
import org.pentaho.di.utils.TestUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TextFileInputTest {
  private StepMockHelper<TextFileInputMeta, TextFileInputData> smh;
  @ClassRule public static RestorePDIEngineEnvironment env = new RestorePDIEngineEnvironment();

  @BeforeClass
  public static void initKettle() throws Exception {
    KettleEnvironment.init();
  }

  @Before
  public void setUp() {
    smh =
      new StepMockHelper<TextFileInputMeta, TextFileInputData>( "TextFileInput", TextFileInputMeta.class,
        TextFileInputData.class );
    when( smh.logChannelInterfaceFactory.create( any(), any( LoggingObjectInterface.class ) ) ).thenReturn(
      smh.logChannelInterface );
    when( smh.trans.isRunning() ).thenReturn( true );
  }

  private static InputStreamReader getInputStreamReader( String data ) throws UnsupportedEncodingException {
    return new InputStreamReader( new ByteArrayInputStream( data.getBytes( ( "UTF-8" ) ) ) );
  }

  @Test
  public void testGetLineDOS() throws KettleFileException, UnsupportedEncodingException {
    String input = "col1\tcol2\tcol3\r\ndata1\tdata2\tdata3\r\n";
    String expected = "col1\tcol2\tcol3";
    String output =
        TextFileInputUtils.getLine( null, getInputStreamReader( input ), TextFileInputMeta.FILE_FORMAT_DOS,
            new StringBuilder( 1000 ) );
    assertEquals( expected, output );
  }

  @Test
  public void testGetLineUnix() throws KettleFileException, UnsupportedEncodingException {
    String input = "col1\tcol2\tcol3\ndata1\tdata2\tdata3\n";
    String expected = "col1\tcol2\tcol3";
    String output =
        TextFileInputUtils.getLine( null, getInputStreamReader( input ), TextFileInputMeta.FILE_FORMAT_UNIX,
            new StringBuilder( 1000 ) );
    assertEquals( expected, output );
  }

  @Test
  public void testGetLineOSX() throws KettleFileException, UnsupportedEncodingException {
    String input = "col1\tcol2\tcol3\rdata1\tdata2\tdata3\r";
    String expected = "col1\tcol2\tcol3";
    String output =
        TextFileInputUtils.getLine( null, getInputStreamReader( input ), TextFileInputMeta.FILE_FORMAT_UNIX,
            new StringBuilder( 1000 ) );
    assertEquals( expected, output );
  }

  @Test
  public void testGetLineMixed() throws KettleFileException, UnsupportedEncodingException {
    String input = "col1\tcol2\tcol3\r\ndata1\tdata2\tdata3\r";
    String expected = "col1\tcol2\tcol3";
    String output =
        TextFileInputUtils.getLine( null, getInputStreamReader( input ), TextFileInputMeta.FILE_FORMAT_MIXED,
            new StringBuilder( 1000 ) );
    assertEquals( expected, output );
  }

  @Test( timeout = 100 )
  public void test_PDI695() throws KettleFileException, UnsupportedEncodingException {
    String inputDOS = "col1\tcol2\tcol3\r\ndata1\tdata2\tdata3\r\n";
    String inputUnix = "col1\tcol2\tcol3\ndata1\tdata2\tdata3\n";
    String inputOSX = "col1\tcol2\tcol3\rdata1\tdata2\tdata3\r";
    String expected = "col1\tcol2\tcol3";

    assertEquals( expected, TextFileInputUtils.getLine( null, getInputStreamReader( inputDOS ),
        TextFileInputMeta.FILE_FORMAT_UNIX, new StringBuilder( 1000 ) ) );
    assertEquals( expected, TextFileInputUtils.getLine( null, getInputStreamReader( inputUnix ),
        TextFileInputMeta.FILE_FORMAT_UNIX, new StringBuilder( 1000 ) ) );
    assertEquals( expected, TextFileInputUtils.getLine( null, getInputStreamReader( inputOSX ),
        TextFileInputMeta.FILE_FORMAT_UNIX, new StringBuilder( 1000 ) ) );
  }

  @Test
  public void readWrappedInputWithoutHeaders() throws Exception {
    final String content = new StringBuilder()
      .append( "r1c1" ).append( '\n' ).append( ";r1c2\n" )
      .append( "r2c1" ).append( '\n' ).append( ";r2c2" )
      .toString();
    final String virtualFile = createVirtualFile( "pdi-2607.txt", content );

    TextFileInputMeta meta = createMetaObject( false, field( "col1" ), field( "col2" ) );
    meta.content.lineWrapped = true;
    meta.content.nrWraps = 1;

    TextFileInputData data = createDataObject( virtualFile, ";", "col1", "col2" );

    TextFileInput input = StepMockUtil.getStep( TextFileInput.class, TextFileInputMeta.class, "test" );
    List<Object[]> output = TransTestingUtil.execute( input, meta, data, 2, false );
    TransTestingUtil.assertResult( new Object[] { "r1c1", "r1c2" }, output.get( 0 ) );
    TransTestingUtil.assertResult( new Object[] { "r2c1", "r2c2" }, output.get( 1 ) );

    deleteVfsFile( virtualFile );
  }

  @Test
  public void readInputWithMissedValues() throws Exception {
    final String virtualFile = createVirtualFile( "pdi-14172.txt", "1,1,1\n", "2,,2\n" );

    BaseFileField field2 = field( "col2" );
    field2.setRepeated( true );

    TextFileInputMeta meta = createMetaObject( false, field( "col1" ), field2, field( "col3" ) );
    TextFileInputData data = createDataObject( virtualFile, ",", "col1", "col2", "col3" );

    TextFileInput input = StepMockUtil.getStep( TextFileInput.class, TextFileInputMeta.class, "test" );
    List<Object[]> output = TransTestingUtil.execute( input, meta, data, 2, false );
    TransTestingUtil.assertResult( new Object[] { "1", "1", "1" }, output.get( 0 ) );
    TransTestingUtil.assertResult( new Object[] { "2", "1", "2" }, output.get( 1 ) );

    deleteVfsFile( virtualFile );
  }

  @Test
  public void readInputWithNonEmptyNullif() throws Exception {
    final String virtualFile = createVirtualFile( "pdi-14358.txt", "-,-\n" );

    BaseFileField col2 = field( "col2" );
    col2.setNullString( "-" );

    TextFileInputMeta meta = createMetaObject( false, field( "col1" ), col2 );
    TextFileInputData data = createDataObject( virtualFile, ",", "col1", "col2" );

    TextFileInput input = StepMockUtil.getStep( TextFileInput.class, TextFileInputMeta.class, "test" );
    List<Object[]> output = TransTestingUtil.execute( input, meta, data, 1, false );
    TransTestingUtil.assertResult( new Object[] { "-" }, output.get( 0 ) );

    deleteVfsFile( virtualFile );
  }

  @Test
  public void readInputWithDefaultValues() throws Exception {
    final String virtualFile = createVirtualFile( "pdi-14832.txt", "1,\n" );

    BaseFileField col2 = field( "col2" );
    col2.setIfNullValue( "DEFAULT" );

    TextFileInputMeta meta = createMetaObject( false, field( "col1" ), col2 );
    TextFileInputData data = createDataObject( virtualFile, ",", "col1", "col2" );

    TextFileInput input = StepMockUtil.getStep( TextFileInput.class, TextFileInputMeta.class, "test" );
    List<Object[]> output = TransTestingUtil.execute( input, meta, data, 1, false );
    TransTestingUtil.assertResult( new Object[] { "1", "DEFAULT" }, output.get( 0 ) );

    deleteVfsFile( virtualFile );
  }
  @Test
  public void testErrorHandlerLineNumber() throws Exception {
    final String content = new StringBuilder()
      .append( "123" ).append( '\n' ).append( "333\n" )
      .append( "345" ).append( '\n' ).append( "773\n" )
      .append( "aaa" ).append( '\n' ).append( "444" )
      .toString();
    final String virtualFile = createVirtualFile( "pdi-2607.txt", content );

    TextFileInputMeta meta = createMetaObject( false, field( "col1" ) );

    meta.inputFields[0].setType( 1 );
    meta.content.lineWrapped = false;
    meta.content.nrWraps = 1;
    meta.errorHandling.errorIgnored = true;
    TextFileInputData data = createDataObject( virtualFile, ";", "col1" );
    data.dataErrorLineHandler = Mockito.mock( FileErrorHandler.class );
    TextFileInput input = StepMockUtil.getStep( TextFileInput.class, TextFileInputMeta.class, "test" );

    List<Object[]> output = TransTestingUtil.execute( input, meta, data, 4, false );

    Mockito.verify( data.dataErrorLineHandler ).handleLineError( 4, AbstractFileErrorHandler.NO_PARTS );
    deleteVfsFile( virtualFile );
  }

  @Test
  public void testHandleOpenFileException() throws Exception {
    final String content = new StringBuilder()
      .append( "123" ).append( '\n' ).append( "333\n" ).toString();
    final String virtualFile = createVirtualFile( "pdi-16697.txt", content );

    TextFileInputMeta meta = createMetaObject( false, field( "col1" ) );

    meta.inputFields[ 0 ].setType( 1 );
    meta.errorHandling.errorIgnored = true;
    meta.errorHandling.skipBadFiles = true;

    TextFileInputData data = createDataObject( virtualFile, ";", "col1" );
    data.dataErrorLineHandler = Mockito.mock( FileErrorHandler.class );

    TestTextFileInput textFileInput = StepMockUtil.getStep( TestTextFileInput.class, TextFileInputMeta.class, "test" );
    StepMeta stepMeta = textFileInput.getStepMeta();
    Mockito.doReturn( true ).when( stepMeta ).isDoingErrorHandling();

    List<Object[]> output = TransTestingUtil.execute( textFileInput, meta, data, 0, false );

    deleteVfsFile( virtualFile );

    assertEquals( 1, data.rejectedFiles.size() );
    assertEquals( 0, textFileInput.getErrors() );
  }

  @Test
  public void test_PDI17117() throws Exception {
    final String virtualFile = createVirtualFile( "pdi-14832.txt", "1,\n" );

    BaseFileField col2 = field( "col2" );
    col2.setIfNullValue( "DEFAULT" );

    TextFileInputMeta meta = createMetaObject( false, field( "col1" ), col2 );

    meta.inputFiles.passingThruFields = true;
    meta.inputFiles.acceptingFilenames = true;
    TextFileInputData data = createDataObject( virtualFile, ",", "col1", "col2" );

    TextFileInput input = Mockito.spy( StepMockUtil.getStep( TextFileInput.class, TextFileInputMeta.class, "test" ) );

    RowSet rowset = Mockito.mock( RowSet.class );
    RowMetaInterface rwi = Mockito.mock( RowMetaInterface.class );
    Object[] obj1 = new Object[2];
    Object[] obj2 = new Object[2];
    Mockito.doReturn( rowset ).when( input ).findInputRowSet( null );
    Mockito.doReturn( null ).when( input ).getRowFrom( rowset );
    Mockito.when( input.getRowFrom( rowset ) ).thenReturn( obj1, obj2, null );
    Mockito.doReturn( rwi ).when( rowset ).getRowMeta();
    Mockito.when( rwi.getString( obj2, 0 ) ).thenReturn( "filename1", "filename2" );
    List<Object[]> output = TransTestingUtil.execute( input, meta, data, 0, false );

    List<String> passThroughKeys = new ArrayList<>( data.passThruFields.keySet() );
    Assert.assertNotNull( passThroughKeys );
    // set order is not guaranteed - order alphabetically
    passThroughKeys.sort( String.CASE_INSENSITIVE_ORDER );
    assertEquals( 2, passThroughKeys.size() );

    Assert.assertNotNull( passThroughKeys.get( 0 ) );
    Assert.assertTrue( passThroughKeys.get( 0 ).startsWith( "0_file" ) );
    Assert.assertTrue( passThroughKeys.get( 0 ).endsWith( "filename1" ) );

    Assert.assertNotNull( passThroughKeys.get( 1 ) );
    Assert.assertTrue( passThroughKeys.get( 1 ).startsWith( "1_file" ) );
    Assert.assertTrue( passThroughKeys.get( 1 ).endsWith( "filename2" ) );

    deleteVfsFile( virtualFile );
  }

  @Test
  public void testClose() throws Exception {

    TextFileInputMeta mockTFIM = createMetaObject( false,null );
    String virtualFile = createVirtualFile( "pdi-17267.txt", null );
    TextFileInputData mockTFID = createDataObject( virtualFile, ";", null );
    mockTFID.lineBuffer = new ArrayList<>();
    mockTFID.lineBuffer.add( new TextFileLine( null, 0l, null ) );
    mockTFID.lineBuffer.add( new TextFileLine( null, 0l, null ) );
    mockTFID.lineBuffer.add( new TextFileLine( null, 0l, null ) );
    mockTFID.filename = "";

    FileContent mockFileContent = mock( FileContent.class );
    InputStream mockInputStream = mock( InputStream.class );
    when( mockFileContent.getInputStream() ).thenReturn( mockInputStream );
    FileObject mockFO = mock( FileObject.class );
    when( mockFO.getContent() ).thenReturn( mockFileContent );

    TextFileInputReader tFIR = new TextFileInputReader( mock( IBaseFileInputStepControl.class ),
      mockTFIM, mockTFID, mockFO, mock( LogChannelInterface.class ) );

    assertEquals( 3, mockTFID.lineBuffer.size() );
    tFIR.close();
    // After closing the file, the buffer must be empty!
    assertEquals( 0, mockTFID.lineBuffer.size() );
  }

  @Test
  public void fieldsWithLineBreaksTest() throws Exception {

    final String content = new StringBuilder()
      .append( "aaa,\"b" ).append( '\n' )
      .append( "bb\",ccc" ).append( '\n' )
      .append( "zzz,yyy,xxx" ).toString();
    final String virtualFile = createVirtualFile( "pdi-18175.txt", content );

    TextFileInputMeta meta = createMetaObject( false,field( "col1" ), field( "col2" ), field( "col3" ) );
    TextFileInputData data = createDataObject( virtualFile, ",", "col1", "col2", "col3" );

    TextFileInput input = StepMockUtil.getStep( TextFileInput.class, TextFileInputMeta.class, "test" );
    List<Object[]> output = TransTestingUtil.execute( input, meta, data, 2, false );
    TransTestingUtil.assertResult( new Object[] { "aaa", "\"bbb\"", "ccc" }, output.get( 0 ) );
    TransTestingUtil.assertResult( new Object[] { "zzz", "yyy", "xxx" }, output.get( 1 ) );

    deleteVfsFile( virtualFile );
  }

  @Test
  public void fieldsWithLineBreaksAndNoEmptyLinesTest() throws Exception {

    final String content = new StringBuilder()
      .append( "aaa,\"b" ).append( '\n' )
      .append( "bb\",ccc" ).append( '\n' )
      .append( '\n' )
      .append( "zzz,yyy,xxx" ).toString();
    final String virtualFile = createVirtualFile( "pdi-18175.txt", content );

    TextFileInputMeta meta = createMetaObject(false, field( "col1" ), field( "col2" ), field( "col3" ) );
    meta.content.noEmptyLines = true;
    TextFileInputData data = createDataObject( virtualFile, ",", "col1", "col2", "col3" );


    TextFileInput input = StepMockUtil.getStep( TextFileInput.class, TextFileInputMeta.class, "test" );
    List<Object[]> output = TransTestingUtil.execute( input, meta, data, 2, false );
    TransTestingUtil.assertResult( new Object[] { "aaa", "\"bbb\"", "ccc" }, output.get( 0 ) );
    TransTestingUtil.assertResult( new Object[] { "zzz", "yyy", "xxx" }, output.get( 1 ) );

    deleteVfsFile( virtualFile );
  }

  @Test
  public void acceptFileOrFolderFromPreviousStep() throws  Exception {
    /*String dataFolder = TestUtils.createRamFolder( "data" );
    String data1Folder = TestUtils.createRamFolder( "data/1" );
    final String content1 = new StringBuilder().append( "12,12-01-01,John,Doe,1000.00" ).append( '\n' ).toString();
    final String data1 = createVirtualFile( "data/1/data.csv", content1 );
    String data2Folder = TestUtils.createRamFolder( "data/2" );
    final String content2 = new StringBuilder().append( "13,11-01-01,John,Murphy,2000.00" ).append( '\n' ).toString();
    final String data2 = createVirtualFile( "data/2/data.csv", content2 );*/
    final boolean acceptingFileNamesFromPreviousStep = true;
    TextFileInputMeta meta = createMetaObject( acceptingFileNamesFromPreviousStep , field( "col1" ), field( "col2" ), field( "col3" )
      , field( "col4" ), field( "col5" ) );
    String fromStep = "Generate Rows";
    String toStep = "Text File Input ";
    String path = "Path";
    meta.inputFiles.acceptingStepName = fromStep;
    meta.inputFiles.acceptingField = path;
    TextFileInputData data = createDataObject( null, ",", "col1", "col2", "col3" );

    TextFileInput input = StepMockUtil.getStep( TextFileInput.class, TextFileInputMeta.class, toStep );

    StepMeta  stepMeta = mock( StepMeta.class );
    when( stepMeta.getName() ).thenReturn( fromStep );
    when( input.getTransMeta().findStep( fromStep ) ).thenReturn( stepMeta );
    input.getTransMeta().setVariable( "test", "test" );
    RowMeta inputRowMeta = new RowMeta();
    ValueMetaString pathMeta = new ValueMetaString( path );
    inputRowMeta.addValueMeta( pathMeta );
    URL res = this.getClass().getResource( "files/frompreviousstep" );
    String filepath = res.getPath();
    Object[] rows = new Object[] { filepath };
    RowSet inputRowSet = new SingleRowRowSet();
    inputRowSet.setThreadNameFromToCopy(fromStep, 0, toStep , 0 );
    inputRowSet.putRow( inputRowMeta, rows );
    List<RowSet> inputRowSets = new ArrayList<RowSet>(  );
    inputRowSets.add( inputRowSet );
    inputRowSet.setRowMeta( inputRowMeta );
    //when(inputRowSet.getOriginStepName()).thenReturn( fromStep );
    input.setInputRowSets( inputRowSets );
    List<Object[]> output = TransTestingUtil.execute( input, meta, data, 2, false );
    TransTestingUtil.assertResult( new Object[] { "12","12-01-01","John,Doe","1000.00" }, output.get( 0 ) );
    TransTestingUtil.assertResult( new Object[] { "13","11-01-01","John,Murphy","2000.00" }, output.get( 0 ) );

   /* deleteVfsFile( dataFolder );
    deleteVfsFile( data1Folder );
    deleteVfsFile( data2Folder );
    deleteVfsFile( data1 );
    deleteVfsFile( data2 );*/

  }

  @Test
  public void fieldsWithLineBreaksWithEmptyLinesTest() throws Exception {

    final String content = new StringBuilder()
      .append( "aaa,\"b" ).append( '\n' )
      .append( "bb\",ccc" ).append( '\n' )
      .append( '\n' )
      .append( "zzz,yyy,xxx" ).toString();
    final String virtualFile = createVirtualFile( "pdi-18175.txt", content );
    TextFileInputMeta meta = createMetaObject( false, field( "col1" ), field( "col2" ), field( "col3" ) );
    meta.content.noEmptyLines = false;
    TextFileInputData data = createDataObject( virtualFile, ",", "col1", "col2", "col3" );


    TextFileInput input = StepMockUtil.getStep( TextFileInput.class, TextFileInputMeta.class, "test" );

    List<Object[]> output = TransTestingUtil.execute( input, meta, data, 3, false );
    TransTestingUtil.assertResult( new Object[] { "aaa", "\"bbb\"", "ccc" }, output.get( 0 ) );
    TransTestingUtil.assertResult( new Object[] { null }, output.get( 1 ) );
    TransTestingUtil.assertResult( new Object[] { "zzz", "yyy", "xxx" }, output.get( 2 ) );

    deleteVfsFile( virtualFile );
  }

  private TextFileInputMeta createMetaObject( boolean acceptingFileNamesFromPreviousStep, BaseFileField... fields ) {
    TextFileInputMeta meta = new TextFileInputMeta();
    meta.content.enclosure = "\"";
    meta.content.fileCompression = "None";
    meta.content.fileType = "CSV";
    meta.content.header = false;
    meta.content.nrHeaderLines = -1;
    meta.content.footer = false;
    meta.content.nrFooterLines = -1;

    meta.inputFields = fields;
    meta.inputFiles.acceptingFilenames =  true;
    return meta;
  }

  private TextFileInputData createDataObject( String file,
                                              String separator,
                                              String... outputFields ) throws Exception {
    TextFileInputData data = new TextFileInputData();
    data.files = new FileInputList();
    if(file != null && file.length() > 0) {
      data.files.addFile( KettleVFS.getFileObject( file ) );
    }


    data.separator = separator;

    data.outputRowMeta = new RowMeta();
    if ( outputFields != null ) {
      for ( String field : outputFields ) {
        data.outputRowMeta.addValueMeta( new ValueMetaString( field ) );
      }
    }

    data.dataErrorLineHandler = mock( FileErrorHandler.class );
    data.fileFormatType = TextFileInputMeta.FILE_FORMAT_UNIX;
    data.filterProcessor = new TextFileFilterProcessor( new TextFileFilter[ 0 ], new Variables() );
    data.filePlayList = new FilePlayListAll();
    return data;
  }
  private static String createVirtualFile( String filename, String... rows ) throws Exception {
    String virtualFile = TestUtils.createRamFile( filename );

    StringBuilder content = new StringBuilder();
    if ( rows != null ) {
      for ( String row : rows ) {
        content.append( row );
      }
    }
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    bos.write( content.toString().getBytes() );

    try ( OutputStream os = KettleVFS.getFileObject( virtualFile ).getContent().getOutputStream() ) {
      IOUtils.copy( new ByteArrayInputStream( bos.toByteArray() ), os );
    }

    return virtualFile;
  }

  private static void deleteVfsFile( String path ) throws Exception {
    FileObject fileObject = TestUtils.getFileObject( path );
    fileObject.close();
    fileObject.delete();
  }

  private static BaseFileField field( String name ) {
    return new BaseFileField( name, -1, -1 );
  }

  public static class TestTextFileInput extends TextFileInput {
    public TestTextFileInput( StepMeta stepMeta, StepDataInterface stepDataInterface, int copyNr, TransMeta transMeta,
                              Trans trans ) {
      super( stepMeta, stepDataInterface, copyNr, transMeta, trans );
    }

    @Override
    protected IBaseFileInputReader createReader( TextFileInputMeta meta, TextFileInputData data, FileObject file )
      throws Exception {
      throw new Exception( "Can not create reader for the file object " + file );
    }
  }
}
