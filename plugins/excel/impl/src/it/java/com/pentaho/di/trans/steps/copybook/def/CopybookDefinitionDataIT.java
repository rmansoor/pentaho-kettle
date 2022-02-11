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

package com.pentaho.di.trans.steps.copybook.def;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.Properties;

import com.pentaho.di.trans.steps.copybook.def.CopybookDefinitionData.FieldInfo;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.pentaho.di.core.KettleClientEnvironment;
import org.pentaho.di.core.Props;
import org.pentaho.di.core.exception.KettleException;
import org.pentaho.di.core.plugins.PluginRegistry;
import org.pentaho.di.core.plugins.StepPluginType;
import org.pentaho.di.core.variables.Variables;

public class CopybookDefinitionDataIT {

  @BeforeClass
  public static void setupBefore() throws KettleException, IOException {

    KettleClientEnvironment.init();
    PluginRegistry.addPluginType( StepPluginType.getInstance() );
    PluginRegistry.init();

    if ( !Props.isInitialized() ) {
      Props.init( 0 );
    }
  }

  @Test
  public void test() throws Exception {

    URI inputDirectory = getClass().getClassLoader().getResource( "input-examples" ).toURI();

    Variables vars = new Variables();

    for ( File file : new File( inputDirectory ).listFiles() ) {
      if ( file.getPath().toLowerCase().endsWith( "cbl" ) ) {

        // Just try to load the file as it is
        CopybookDefinitionMeta meta = new CopybookDefinitionMeta();
        meta.setDefinitionFile( file.getPath() ).setCharsetName( "IBM037" ).setUseLegacyPathNaming( false )
            .setLineStructure( CopybookLineStructureEnum.STANDARD_COLS_6_TO_72.name() );

        CopybookDefinitionData data = new CopybookDefinitionData( meta, vars, null, true );

        File expectedFile = new File( file + ".expected" );
        if ( expectedFile.exists() ) {

          Properties expectedProperties = new Properties();
          expectedProperties.load( new FileInputStream( expectedFile ) );

          if ( expectedProperties.containsKey( "record.length" ) ) {
            Assert.assertEquals( Integer.parseInt( expectedProperties.getProperty( "record.length" ) ),
              data.getMaxRecordLength() );
          }

          if ( expectedProperties.containsKey( "parent.names" ) ) {
            testFieldNames( data, expectedProperties.getProperty( "parent.names" ) );
          }

          if ( expectedProperties.containsKey( "field.names" ) ) {
            testFieldNames( new CopybookDefinitionData( meta, vars, null, false ),
              expectedProperties.getProperty( "field.names" ) );
          }

          if ( expectedProperties.containsKey( "legacy.names" ) ) {
            meta.setUseLegacyPathNaming( true );
            testFieldNames( new CopybookDefinitionData( meta, vars, null, false ),
              expectedProperties.getProperty( "legacy.names" ) );
          }

        }
      }
    }

  }

  private void testFieldNames( CopybookDefinitionData data, String fieldsList ) {

    String[] fieldNames = fieldsList.split( ";" );
    String[] actualNames = new String[data.getFieldCount()];
    int i = 0;
    for ( FieldInfo fi : data.getFieldInfo() ) {
      actualNames[i++] = fi.getQualifiedName();
    }

    Assert.assertArrayEquals( fieldNames, actualNames );

  }

}
