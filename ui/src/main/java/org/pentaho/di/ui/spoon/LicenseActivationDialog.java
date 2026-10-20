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

package org.pentaho.di.ui.spoon;

import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.pentaho.di.license.Activator;
import org.pentaho.di.license.LicenseException;
import org.pentaho.di.license.LicenseValidationResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * Shown by Spoon at startup when this machine isn't licensed: the customer's whole activation, without a command
 * line. Step 1 takes the license key and shows the request to send to Hitachi Vantara (Copy, Save); step 2 takes
 * the activation code that comes back (paste, or open the file). The window can be closed while waiting for the
 * answer: the request is kept, and the next start opens straight on step 2.
 * <p>
 * Plain SWT on purpose: it opens before Spoon's look and feel and properties are set up.
 */
public class LicenseActivationDialog {

  private static final int WIDTH_HINT = 560;

  private final Display display;
  private final String problem;
  private Shell shell;
  private Composite page;
  private boolean activated;

  /**
   * @param problem why activation is needed (the license check's message), or null
   */
  public LicenseActivationDialog( Display display, String problem ) {
    this.display = display;
    this.problem = problem;
  }

  /** @return true once this machine is activated; false if the user quit */
  public boolean open() {
    shell = new Shell( display, SWT.TITLE | SWT.CLOSE | SWT.RESIZE | SWT.APPLICATION_MODAL );
    shell.setText( "Activate Pentaho Data Integration" );
    shell.setLayout( new GridLayout( 1, false ) );
    Optional<String> pending = Activator.pending();
    if ( pending.isPresent() ) {
      showStepTwo( pending.get(), null );
    } else {
      showStepOne( problem );
    }
    shell.open();
    while ( !shell.isDisposed() ) {
      if ( !display.readAndDispatch() ) {
        display.sleep();
      }
    }
    return activated;
  }

  private void showStepOne( String message ) {
    Composite c = newPage();
    heading( c, "Step 1 of 2: your license key" );
    if ( message != null ) {
      note( c, message, true );
    }
    note( c, "Paste the license key you received from Hitachi Vantara with your purchase. It starts with HELIX1-."
      + " Renewed an expired license? Paste the activation code that came with the renewal (HELIXACT-) here.", false );
    Text key = textBox( c, 4, false );
    Label error = errorLabel( c );
    Composite buttons = buttonRow( c, 3 );
    button( buttons, "Open file...", () -> openFileInto( key ) );
    button( buttons, "Quit", shell::close );
    Button next = button( buttons, "Next", () -> {
      try {
        if ( Activator.isActivationCode( key.getText() ) ) {
          LicenseValidationResult result = new Activator().complete( key.getText() );
          if ( result.isValid() ) {
            activated = true;
            shell.close();
          } else {
            showError( error, result.getMessage() );
          }
          return;
        }
        showStepTwo( new Activator().start( key.getText() ), null );
      } catch ( LicenseException e ) {
        showError( error, e.getMessage() );
      }
    } );
    shell.setDefaultButton( next );
    layout();
    key.setFocus();
  }

  private void showStepTwo( String request, String message ) {
    Composite c = newPage();
    heading( c, "Step 2 of 2: send the request, paste the answer" );
    if ( message != null ) {
      note( c, message, true );
    }
    note( c, "Send this activation request to Hitachi Vantara from any computer (email or the activation portal)."
      + " This computer doesn't need a network connection.", false );
    Text requestText = textBox( c, 4, true );
    requestText.setText( request );
    Composite copyRow = buttonRow( c, 2 );
    button( copyRow, "Copy", () -> copy( request ) );
    button( copyRow, "Save to file...", () -> save( request ) );

    note( c, "Paste the activation code you receive (it starts with HELIXACT-), or open the file you received."
      + " You can close this window and come back: the request is kept.", false );
    Text answer = textBox( c, 4, false );
    Label error = errorLabel( c );
    Composite buttons = buttonRow( c, 4 );
    button( buttons, "Use another key", () -> {
      Activator.cancel();
      showStepOne( null );
    } );
    button( buttons, "Open file...", () -> openFileInto( answer ) );
    button( buttons, "Finish later", shell::close );
    Button activate = button( buttons, "Activate", () -> {
      try {
        LicenseValidationResult result = new Activator().complete( answer.getText() );
        if ( result.isValid() ) {
          activated = true;
          shell.close();
        } else {
          showError( error, result.getMessage() );
        }
      } catch ( LicenseException e ) {
        showError( error, e.getMessage() );
      }
    } );
    shell.setDefaultButton( activate );
    layout();
    answer.setFocus();
  }

  private Composite newPage() {
    if ( page != null ) {
      page.dispose();
    }
    page = new Composite( shell, SWT.NONE );
    GridLayout layout = new GridLayout( 1, false );
    layout.marginWidth = 12;
    layout.marginHeight = 12;
    layout.verticalSpacing = 10;
    page.setLayout( layout );
    page.setLayoutData( new GridData( SWT.FILL, SWT.FILL, true, true ) );
    return page;
  }

  private void heading( Composite parent, String words ) {
    Label label = new Label( parent, SWT.WRAP );
    label.setText( words );
    label.setFont( org.eclipse.jface.resource.JFaceResources.getBannerFont() );
    label.setLayoutData( wide() );
  }

  private void note( Composite parent, String words, boolean warning ) {
    Label label = new Label( parent, SWT.WRAP );
    label.setText( words );
    if ( warning ) {
      label.setForeground( display.getSystemColor( SWT.COLOR_DARK_RED ) );
    }
    label.setLayoutData( wide() );
  }

  private Text textBox( Composite parent, int lines, boolean readOnly ) {
    Text text = new Text( parent, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL | ( readOnly ? SWT.READ_ONLY : 0 ) );
    GridData data = wide();
    data.heightHint = text.getLineHeight() * lines;
    text.setLayoutData( data );
    return text;
  }

  private Label errorLabel( Composite parent ) {
    Label label = new Label( parent, SWT.WRAP );
    label.setForeground( display.getSystemColor( SWT.COLOR_RED ) );
    GridData data = wide();
    data.exclude = true;
    label.setLayoutData( data );
    label.setVisible( false );
    return label;
  }

  private void showError( Label label, String message ) {
    label.setText( message );
    ( (GridData) label.getLayoutData() ).exclude = false;
    label.setVisible( true );
    layout();
  }

  private Composite buttonRow( Composite parent, int columns ) {
    Composite row = new Composite( parent, SWT.NONE );
    GridLayout layout = new GridLayout( columns, false );
    layout.marginWidth = 0;
    row.setLayout( layout );
    row.setLayoutData( new GridData( SWT.END, SWT.CENTER, true, false ) );
    return row;
  }

  private Button button( Composite parent, String words, Runnable action ) {
    Button b = new Button( parent, SWT.PUSH );
    b.setText( words );
    b.addListener( SWT.Selection, e -> action.run() );
    return b;
  }

  private GridData wide() {
    GridData data = new GridData( SWT.FILL, SWT.CENTER, true, false );
    data.widthHint = WIDTH_HINT;
    return data;
  }

  private void copy( String text ) {
    Clipboard clipboard = new Clipboard( display );
    try {
      clipboard.setContents( new Object[] { text }, new Transfer[] { TextTransfer.getInstance() } );
    } finally {
      clipboard.dispose();
    }
  }

  private void save( String text ) {
    FileDialog dialog = new FileDialog( shell, SWT.SAVE );
    dialog.setFileName( "activation-request.txt" );
    dialog.setOverwrite( true );
    String path = dialog.open();
    if ( path != null ) {
      try {
        Files.write( Paths.get( path ), ( text + System.lineSeparator() ).getBytes( StandardCharsets.UTF_8 ) );
      } catch ( IOException e ) {
        org.eclipse.swt.widgets.MessageBox box = new org.eclipse.swt.widgets.MessageBox( shell, SWT.ICON_ERROR | SWT.OK );
        box.setMessage( "Could not save " + path + ": " + e.getMessage() );
        box.open();
      }
    }
  }

  private void openFileInto( Text target ) {
    FileDialog dialog = new FileDialog( shell, SWT.OPEN );
    String path = dialog.open();
    if ( path != null ) {
      try {
        target.setText( new String( Files.readAllBytes( Paths.get( path ) ), StandardCharsets.UTF_8 ).trim() );
      } catch ( IOException e ) {
        target.setText( "" );
      }
    }
  }

  private void layout() {
    shell.layout( true, true );
    org.eclipse.swt.graphics.Point size = shell.computeSize( SWT.DEFAULT, SWT.DEFAULT );
    shell.setSize( size );
    Control parent = shell.getParent();
    org.eclipse.swt.graphics.Rectangle area = display.getPrimaryMonitor().getClientArea();
    if ( parent == null ) {
      shell.setLocation( area.x + ( area.width - size.x ) / 2, area.y + ( area.height - size.y ) / 3 );
    }
  }
}
