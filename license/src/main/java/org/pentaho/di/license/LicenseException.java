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

package org.pentaho.di.license;

/**
 * Thrown when a license key string is malformed, or when license enforcement determines the running
 * process is not licensed and must not start.
 */
public class LicenseException extends Exception {

  private static final long serialVersionUID = 1L;

  public LicenseException( String message ) {
    super( message );
  }

  public LicenseException( String message, Throwable cause ) {
    super( message, cause );
  }
}
