
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

import java.io.FileInputStream;
import java.io.StringWriter;
import java.util.concurrent.atomic.AtomicInteger;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Node;

import net.sf.JRecord.Common.Constants;
import net.sf.JRecord.Common.Conversion;
import net.sf.JRecord.Common.FieldDetail;
import net.sf.JRecord.Common.IFieldDetail;
import net.sf.JRecord.Details.AbstractLine;
import net.sf.JRecord.Details.LayoutDetail;
import net.sf.JRecord.External.CobolCopybookLoader;
import net.sf.JRecord.External.CopybookLoader;
import net.sf.JRecord.External.ExternalRecord;
import net.sf.JRecord.External.XmlCopybookLoader;
import net.sf.JRecord.IO.AbstractLineReader;
import net.sf.JRecord.IO.LineIOProvider;
import net.sf.JRecord.Log.AbsSSLogger;
import net.sf.JRecord.Numeric.ICopybookDialects;
import net.sf.JRecord.Types.Type;
import net.sf.JRecord.Types.TypeManager;
import net.sf.JRecord.Types.TypePackedDecimal;
import net.sf.cb2xml.def.Cb2xmlConstants;

/**
 * This class is used for exploratory purposes. It currently doesn't contain any unit tests
 * 
 * @author jjarvis
 *
 */
public class App {

  public static void main( String[] args ) throws Exception {

    // System.out.println( Charset.forName( defaultCharse ).name() );

    /*
     * for( Entry<String, Charset> s : Charset.availableCharsets().entrySet() ) { System.out.print( s.getKey() ); for (
     * String a : s.getValue().aliases() ) { System.out.print( ", " + a ); } System.out.println( ); }
     * 
     * System.exit( 0 );
     */

    try ( FileInputStream fis = new FileInputStream( args[0] ); ) {

      XmlCopybookLoader loader = new XmlCopybookLoader() {
        @Override
        public ExternalRecord loadDOMCopyBook( Document arg0, String arg1, int arg2, int arg3, String arg4, int arg5,
            int arg6 ) {

          System.out.println( getStringFromDocument( arg0 ) );

          System.out.println( "Child Nodes: " + arg0.getChildNodes().getLength() );

          Node copybookNode = arg0.getFirstChild();

          System.out.println( copybookNode.getChildNodes().getLength() );

          for ( int i = 0; i < copybookNode.getChildNodes().getLength(); i++ ) {

            recurseNode( copybookNode.getChildNodes().item( i ), "", new AtomicInteger( 0 ), 0 );

          }

          return super.loadDOMCopyBook( arg0, arg1, arg2, arg3, arg4, arg5, arg6 );
        }

        public void recurseNode( Node n, String parent, AtomicInteger fillerIndex, int positionOffset ) {

          if ( n == null || n.getAttributes() == null || !n.getNodeName().equalsIgnoreCase( "item" ) ) {
            return;
          }

          String fieldName = n.getAttributes().getNamedItem( "name" ).getNodeValue();

          int position = Integer.parseInt( n.getAttributes().getNamedItem( "position" ).getNodeValue() );
          int length = Integer.parseInt( n.getAttributes().getNamedItem( "storage-length" ).getNodeValue() );

          if ( fieldName.equals( "FILLER" ) ) {
            fieldName += "[" + fillerIndex.incrementAndGet() + "]";
          }

          String name = parent + ( parent.isEmpty() ? "" : "." ) + fieldName;

          System.out.print( name );

          Node occurs = n.getAttributes().getNamedItem( "occurs" );
          if ( occurs != null ) {
            System.out.print( " - occurs " + occurs.getNodeValue() );
          }
          int repTimes = occurs != null ? Integer.parseInt( occurs.getNodeValue() ) : 1;

          System.out.print( " - " + ( positionOffset + position ) + ", " + ( length * repTimes ) );

          if ( hasChildItem( n ) ) {

            System.out.println();

            for ( int j = 0; j < repTimes; j++ ) {

              AtomicInteger groupFillerIndex = new AtomicInteger( 0 );

              for ( int i = 0; i < n.getChildNodes().getLength(); i++ ) {

                recurseNode( n.getChildNodes().item( i ), name + ( ( occurs != null ) ? "[" + ( j + 1 ) + "]" : "" ),
                  groupFillerIndex, positionOffset );

              }

              if ( occurs != null ) {
                positionOffset += length;
              }

            }

          } else {

            for ( int i = 0; i < repTimes; i++ ) {

              System.out.print( name + ( ( occurs != null ) ? "[" + ( i + 1 ) + "]" : "" ) );

            }

            System.out.println( " - leaf" );
          }

        }

        private boolean hasChildItem( Node n ) {
          if ( n.hasChildNodes() ) {
            Node curr = n.getFirstChild();
            do {
              if ( curr.getNodeName().equalsIgnoreCase( "item" ) ) {
                return true;
              }
            } while ( ( curr = curr.getNextSibling() ) != null );
          }
          return false;
        }

        public String getStringFromDocument( Document doc ) {

          try {
            DOMSource domSource = new DOMSource( doc );
            StringWriter writer = new StringWriter();
            StreamResult result = new StreamResult( writer );
            TransformerFactory tf = TransformerFactory.newInstance();
            Transformer transformer = tf.newTransformer();
            transformer.setOutputProperty( OutputKeys.INDENT, "yes" );
            transformer.transform( domSource, result );
            return writer.toString();
          } catch ( TransformerException ex ) {
            ex.printStackTrace();
            return null;
          }
        }

      };

      CobolCopybookLoader cbLoader = new CobolCopybookLoader( );
      cbLoader.setDropCopybookFromFieldNames( true );
      cbLoader.setKeepFillers( true );
      ExternalRecord er =
          cbLoader.loadCopyBook( fis, "x", CopybookLoader.SPLIT_NONE, 0, "Cp037", Cb2xmlConstants.USE_STANDARD_COLUMNS,
            ICopybookDialects.FMT_MAINFRAME, new AbsSSLogger() {

              @Override
              public void setReportLevel( int arg0 ) {
                System.out.println( arg0 );

              }

              @Override
              public void logMsg( int arg0, String arg1 ) {
                System.out.println( arg0 + " : " + arg1 );

              }

              @Override
              public void logException( int arg0, Exception arg1 ) {
                arg1.printStackTrace();
              }

            } ).setFileStructure( Constants.IO_FIXED_LENGTH );

      LayoutDetail ld = er.asLayoutDetail();

      System.out.println();
      System.out.println();

      for ( FieldDetail fd : ld.getRecord( 0 ).getFields() ) {
        System.out.println( fd.getGroupName() + fd.getName() + ", " + fd.getPos() + ", " + fd.getLen() );
      }

      System.exit( 0 );

      IFieldDetail detail = ld.getFieldFromName( "ML_ACCRU_ADJ" );

      TypeManager.getInstance().registerType( Type.USER_RANGE_START, new TypePackedDecimal( false ) {

        @Override
        public Object getField( byte[] record, int position, IFieldDetail field ) {

          int pos = position - 1;
          int end = position + field.getLen() - 1;
          int min = java.lang.Math.min( end, record.length );
          int fldLength = min - pos;

          String s =
              getMainframePackedDecimal( record,
                pos,
                fldLength );

          return addDecimalPoint( s, field.getDecimal() );
        }

        private String getMainframePackedDecimal( final byte[] record, final int start, final int len ) {
          String hex = Conversion.getDecimal( record, start, start + len );
          String ret = "";
          String sign = "";

          if ( !"".equals( hex ) ) {
            switch ( hex.substring( hex.length() - 1 ).toLowerCase().charAt( 0 ) ) {
              case 'd':
                sign = "-";
              case '0':
              case '1':
              case '2':
              case '3':
              case '4':
              case '5':
              case '6':
              case '7':
              case '8':
              case '9':
              case 'a':
              case 'b':
              case 'c':
              case 'e':
              case 'f':
                ret = sign + hex.substring( 0, hex.length() - 1 );
                break;
              default:
                ret = hex;
            }
          }

          if ( "".equals( ret ) ) {
            ret = "0";
          }

          return ret;
        }

      } );

      ( (FieldDetail) detail ).setNameType( detail.getName(), Type.USER_RANGE_START );

      AbstractLineReader alr = new LineIOProvider().getLineReader( ld );

      try ( FileInputStream fisData =
          new FileInputStream(
              args[1] ) ) {

        alr.open( fisData, ld );

        AbstractLine line;
        while ( ( line = alr.read() ) != null ) {

          System.out.println( line.getFieldValue( "ML_ACCRU_ADJ" ).asBigDecimal() );

          System.out.println( line.getFieldValue( "ML_ACCRU_ADJ" ).asHex() );

        }

      }

    }

  }

}
