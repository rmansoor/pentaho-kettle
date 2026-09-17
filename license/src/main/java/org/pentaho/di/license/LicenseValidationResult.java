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
 * The outcome of {@link LicenseVerifier#verify(LicenseKey)}: either {@link #valid(LicensePayload)}, or
 * a {@link #status} explaining why not. Deliberately doesn't throw for the "not licensed" case &mdash;
 * that's an expected, common outcome, not an exceptional one.
 */
public final class LicenseValidationResult {

  public enum Status {
    VALID,
    INVALID_SIGNATURE,
    EXPIRED,
    MALFORMED
  }

  private final Status status;
  private final LicensePayload payload;
  private final String message;

  private LicenseValidationResult( Status status, LicensePayload payload, String message ) {
    this.status = status;
    this.payload = payload;
    this.message = message;
  }

  static LicenseValidationResult valid( LicensePayload payload ) {
    return new LicenseValidationResult( Status.VALID, payload, "License is valid" );
  }

  static LicenseValidationResult invalidSignature() {
    return new LicenseValidationResult( Status.INVALID_SIGNATURE, null,
      "License key signature does not match — this key was not issued by Hitachi Vantara, "
        + "or has been altered" );
  }

  static LicenseValidationResult expired( LicensePayload payload ) {
    return new LicenseValidationResult( Status.EXPIRED, payload, "License expired" );
  }

  static LicenseValidationResult malformed( String reason ) {
    return new LicenseValidationResult( Status.MALFORMED, null, "License key is invalid: " + reason );
  }

  public boolean isValid() {
    return status == Status.VALID;
  }

  public Status getStatus() {
    return status;
  }

  /** The verified payload, present only when {@link #isValid()} (or when {@link #getStatus()} is EXPIRED). */
  public LicensePayload getPayload() {
    return payload;
  }

  public String getMessage() {
    return message;
  }

  @Override
  public String toString() {
    return "LicenseValidationResult{" + status + ": " + message + "}";
  }
}
