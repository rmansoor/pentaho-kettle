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
package com.pentaho.di.trans.steps.copybook.meta;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.pentaho.di.trans.steps.copybook.def.CopybookDialectEnum;
import com.pentaho.di.trans.steps.copybook.def.CopybookLineStructureEnum;
import com.pentaho.di.trans.steps.copybook.util.CopybookTestUtilities;
import com.pentaho.di.trans.steps.copybook.util.JRecordTypeEnum;
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
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.trans.Trans;
import org.pentaho.di.trans.TransHopMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.RowListener;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.steps.dummytrans.DummyTransMeta;


@RunWith( MockitoJUnitRunner.class )
public class CopybookMetaStepIT {

  @Mock
  LogChannelInterfaceFactory logChannelFactory;

  @Mock
  LogChannelInterface logChannel;

  @Before
  public void setup() throws IOException {

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

    StepPluginType.getInstance().handlePluginAnnotation( CopybookMetaStepMeta.class,
        CopybookMetaStepMeta.class.getAnnotation( Step.class ), Collections.<String>emptyList(), false, null );

    CopybookTestUtilities.createTestFiles();

  }

  @AfterClass
  public static void cleanupAfter() throws IOException, KettleFileException {
    CopybookTestUtilities.deleteTestFiles();
  }

  @Test
  public void test() throws KettleException, IOException, URISyntaxException, InterruptedException {

    TransMeta transMeta = new TransMeta();
    transMeta.setName( "Test Copybook Meta" );

    final String copybookStepName = "Copybook Meta Input";

    CopybookMetaStepMeta meta = new CopybookMetaStepMeta();
    StepMeta stepMeta =
        new StepMeta( PluginRegistry.getInstance().getPluginId( StepPluginType.class, meta ), copybookStepName,
            meta );
    transMeta.addStep( stepMeta );
    meta.getDefinitionMeta().setAllowMalformedSign( false );
    meta.getDefinitionMeta().setSourceArchitecture( CopybookDialectEnum.BIG_ENDIAN_MAINFRAME.toString() );
    meta.getDefinitionMeta().setLineStructure( CopybookLineStructureEnum.STANDARD_COLS_6_TO_72.toString() );
    meta.getDefinitionMeta().setCharsetName( CopybookDialectEnum.BIG_ENDIAN_MAINFRAME.getPreferredCharset() );
    meta.getDefinitionMeta().setDefinitionFile( CopybookTestUtilities.DEFINITION_FILE );

    DummyTransMeta dMeta = new DummyTransMeta();
    StepMeta dStepMeta =
        new StepMeta( PluginRegistry.getInstance().getPluginId( StepPluginType.class, dMeta ), "Dummy Step", dMeta );
    transMeta.addStep( dStepMeta );

    transMeta.addTransHop( new TransHopMeta( stepMeta, dStepMeta ) );

    Trans trans = new Trans( transMeta );
    trans.prepareExecution( null );

    final List<Object[]> rowData = new ArrayList<>();

    trans.getStepInterface( copybookStepName, 0 ).addRowListener( new RowListener() {

      @Override
      public void errorRowWrittenEvent( RowMetaInterface rmi, Object[] row ) throws KettleStepException {
      }

      @Override
      public void rowReadEvent( RowMetaInterface rmi, Object[] row ) throws KettleStepException {
      }

      @Override
      public void rowWrittenEvent( RowMetaInterface rmi, Object[] row ) throws KettleStepException {
        rowData.add( row );
      }

    } );

    trans.startThreads();
    trans.waitUntilFinished();

    Assert.assertEquals( 0, trans.getErrors() );

    List<Object[]> goldenValues =
        Arrays.asList(
            new Object[] {
              CopybookTestUtilities.DEFINITION_FILE, new Long( 19 ), new Long( 5 ), "S-TEST",
              "TEST.S-TEST", new Long( 1 ), new Long( 6 ), JRecordTypeEnum.CHAR.toString(),
              JRecordTypeEnum.CHAR.getKettleType().toString(), null, null },
            new Object[] {
              CopybookTestUtilities.DEFINITION_FILE, new Long( 19 ), new Long( 5 ), "C-TEST",
              "TEST.C-TEST", new Long( 7 ), new Long( 3 ), JRecordTypeEnum.NUM_PACKED_SMALL_DEC.toString(),
              JRecordTypeEnum.NUM_PACKED_DEC.getKettleType().toString(), new Long( 1 ), null },
            new Object[] {
              CopybookTestUtilities.DEFINITION_FILE, new Long( 19 ), new Long( 5 ), "U-TEST",
              "TEST.U-TEST", new Long( 10 ), new Long( 2 ), JRecordTypeEnum.NUM_PACKED_SMALL_DEC_POS.toString(),
              JRecordTypeEnum.NUM_PACKED_DEC_POS.getKettleType().toString(), new Long( 0 ), null },
            new Object[] {
              CopybookTestUtilities.DEFINITION_FILE, new Long( 19 ), new Long( 5 ), "D-TEST",
              "TEST.D-TEST", new Long( 12 ), new Long( 4 ), JRecordTypeEnum.NUM_ZERO_PADDED_POS.toString(),
              JRecordTypeEnum.NUM_ZERO_PADDED_POS.getKettleType().toString(), new Long( 0 ), null },
            new Object[] {
              CopybookTestUtilities.DEFINITION_FILE, new Long( 19 ), new Long( 5 ), "B-TEST",
              "TEST.B-TEST", new Long( 16 ), new Long( 4 ), JRecordTypeEnum.NUM_BIG_END_SMALL_BIN.toString(),
              JRecordTypeEnum.NUM_BIG_END_BIN.getKettleType().toString(), new Long( 0 ), null } );

    CopybookTestUtilities.compareToGolden( goldenValues, rowData );

  }

}
