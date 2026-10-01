/*!
* Copyright (C) 2026 by Hitachi Vantara : http://www.pentaho.com
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package org.pentaho.googledrive.vfs.util;

import org.junit.After;
import org.junit.Test;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CustomLocalServerReceiverTest {

  private final CustomLocalServerReceiver receiver = new CustomLocalServerReceiver();

  @After
  public void stop() throws Exception {
    receiver.stop();
  }

  @Test
  public void capturesTheCodeAndServesTheSuccessPage() throws Exception {
    String redirect = receiver.getRedirectUri();
    assertTrue( redirect, redirect.matches( "http://localhost:\\d+/Callback/success\\.html" ) );

    HttpURLConnection c = open( redirect + "?code=4%2Fabc&scope=x" );
    assertEquals( 200, c.getResponseCode() );
    assertTrue( c.getContentType().startsWith( "text/html" ) );
    try ( InputStream in = c.getInputStream() ) {
      assertTrue( new String( in.readAllBytes(), StandardCharsets.UTF_8 ).contains( "<html" ) );
    }
    assertEquals( "4/abc", receiver.waitForCode() );

    HttpURLConnection image = open( "http://localhost:" + receiver.getPort() + "/Callback/success.png" );
    assertEquals( 200, image.getResponseCode() );
    assertEquals( "image/png", image.getContentType() );
    assertEquals( 404, open( "http://localhost:" + receiver.getPort() + "/Callback/../x" ).getResponseCode() );
  }

  @Test
  public void deniedAccessGoesBackToTheGivenPage() throws Exception {
    receiver.setUrl( "http://localhost/denied" );
    String redirect = receiver.getRedirectUri();

    HttpURLConnection c = open( redirect + "?error=access_denied" );
    assertEquals( 302, c.getResponseCode() );
    assertEquals( "http://localhost/denied", c.getHeaderField( "Location" ) );
    assertEquals( "access_denied", receiver.error );
  }

  private static HttpURLConnection open( String url ) throws Exception {
    HttpURLConnection c = (HttpURLConnection) new URL( url ).openConnection();
    c.setInstanceFollowRedirects( false );
    return c;
  }
}
