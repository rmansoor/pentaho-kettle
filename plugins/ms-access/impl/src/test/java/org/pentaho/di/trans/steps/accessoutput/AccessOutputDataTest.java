/*! ******************************************************************************
 *
 * Pentaho Data Integration
 *
 * Copyright (C) 2002-2018 by Hitachi Vantara : http://www.pentaho.com
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

package org.pentaho.di.trans.steps.accessoutput;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Date;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.Before;
import org.junit.BeforeClass;
import org.pentaho.di.core.KettleEnvironment;
import org.junit.Test;
import org.pentaho.di.core.row.RowMeta;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.core.row.value.ValueMetaBigNumber;
import org.pentaho.di.core.row.value.ValueMetaBoolean;
import org.pentaho.di.core.row.value.ValueMetaDate;
import org.pentaho.di.core.row.value.ValueMetaInteger;
import org.pentaho.di.core.row.value.ValueMetaNumber;
import org.pentaho.di.core.row.value.ValueMetaString;

import com.healthmarketscience.jackcess.Database;
import com.healthmarketscience.jackcess.Row;
import com.healthmarketscience.jackcess.Table;

public class AccessOutputDataTest {

  AccessOutputData data;
  File mdbFile;

  @BeforeClass
  public static void setUpBeforeClass() throws Exception {
    KettleEnvironment.init( false );
  }

  @Before
  public void setUp() throws IOException {
    data = new AccessOutputData();
    mdbFile = File.createTempFile( "PDI_AccessOutputDataTest", ".mdb" );
    mdbFile.deleteOnExit();
  }

  RowMetaInterface generateRowMeta() {
    RowMetaInterface row = new RowMeta();
    row.addValueMeta( new ValueMetaInteger( "id" ) );
    row.addValueMeta( new ValueMetaString( "UUID" ) );
    return row;
  }

  List<Object[]> generateRowData( int rowCount ) {
    List<Object[]> rows = new ArrayList<Object[]>();
    for ( int i = 0; i < rowCount; i++ ) {
      rows.add( new Object[]{ i, UUID.randomUUID().toString() } );
    }
    return rows;
  }

  @Test
  public void testCreateDatabase() throws IOException {
    assertNull( data.db );
    data.createDatabase( mdbFile );
    assertNotNull( data.db );
    assertTrue( mdbFile.exists() );

    assertNull( data.table );
    data.truncateTable();
    assertNull( data.table );

    data.closeDatabase();
  }

  @Test
  public void testCreateTable() throws IOException {
    data.createDatabase( mdbFile );
    data.createTable( "thisSampleTable", generateRowMeta() );
    assertTrue( data.db.getTableNames().contains( "thisSampleTable" ) );
    data.closeDatabase();
  }

  @Test
  public void testTruncateTable() throws IOException {
    data.createDatabase( mdbFile );
    data.createTable( "TruncatingThisTable", generateRowMeta() );

    data.addRowsToTable( generateRowData( 10 ) );
    assertEquals( 10, data.table.getRowCount() );

    data.truncateTable();
    assertEquals( 0, data.table.getRowCount() );

    data.addRowToTable( generateRowData( 1 ).get( 0 ) );
    assertEquals( 1, data.table.getRowCount() );
    data.closeDatabase();
  }

  // jackcess 4: Access Output writes what jackcess 1.x wrote (an Access 2000 file, java.util.Date for dates), and
  // the file reads back through the same open path Access Input uses
  @Test
  public void testRoundTripTypesAndFormat() throws Exception {
    RowMetaInterface rowMeta = new RowMeta();
    ValueMetaInteger id = new ValueMetaInteger( "id" );
    id.setLength( 9 );
    rowMeta.addValueMeta( id );
    rowMeta.addValueMeta( new ValueMetaNumber( "amount" ) );
    rowMeta.addValueMeta( new ValueMetaDate( "created" ) );
    ValueMetaString name = new ValueMetaString( "name" );
    name.setLength( 50 );
    rowMeta.addValueMeta( name );
    rowMeta.addValueMeta( new ValueMetaBoolean( "active" ) );
    ValueMetaBigNumber price = new ValueMetaBigNumber( "price" );
    price.setPrecision( 10 );
    rowMeta.addValueMeta( price );

    Date created = new Date( 1_700_000_000_000L - 1_700_000_000_000L % 1000 );
    data.createDatabase( mdbFile );
    data.createTable( "roundtrip", rowMeta );
    // a BigNumber's precision becomes the NUMERIC column's precision with scale 0 (as with jackcess 1.2.6), so the
    // column only stores whole numbers
    data.addRowToTable( 7L, 12.5d, created, "caf\u00e9", Boolean.TRUE, new BigDecimal( "325" ) );
    data.closeDatabase();

    Database db = AccessOutputMeta.openDatabase( mdbFile, true );
    try {
      assertEquals( Database.FileFormat.V2000, db.getFileFormat() );
      Table table = db.getTable( "roundtrip" );
      assertEquals( 1, table.getRowCount() );
      Row row = table.getNextRow();
      assertEquals( 7, ( (Number) row.get( "id" ) ).intValue() );
      assertEquals( 12.5d, (Double) row.get( "amount" ), 0d );
      assertTrue( row.get( "created" ) instanceof Date );
      assertEquals( created, row.get( "created" ) );
      assertEquals( "caf\u00e9", row.get( "name" ) );
      assertEquals( Boolean.TRUE, row.get( "active" ) );
      assertEquals( 0, new BigDecimal( "325" ).compareTo( (BigDecimal) row.get( "price" ) ) );
      assertEquals( 6, AccessOutputMeta.getLayout( table ).size() );
    } finally {
      db.close();
    }
  }
}
