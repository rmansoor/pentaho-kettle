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
 * The outcome of {@link LicenseVerifier}: either valid, or a {@link #getStatus() status} explaining why not.
 * Deliberately doesn't throw for the "not licensed" cases &mdash; those are expected, common outcomes, not
 * exceptional ones.
 */
public final class LicenseValidationResult {

  public enum Status {
    VALID,
    /** Nothing installed. */
    NOT_INSTALLED,
    /** A genuine entitlement key, but entitlement keys must be activated for a machine first. */
    NOT_ACTIVATED,
    /** A genuine activation, for another machine. */
    WRONG_MACHINE,
    INVALID_SIGNATURE,
    EXPIRED,
    /** The system clock is earlier than a time PDI has already seen (set back to extend a license). */
    CLOCK_ROLLBACK,
    MALFORMED
  }

  private final Status status;
  private final LicensePayload payload;
  private final Activation activation;
  private final String message;

  private LicenseValidationResult( Status status, LicensePayload payload, Activation activation, String message ) {
    this.status = status;
    this.payload = payload;
    this.activation = activation;
    this.message = message;
  }

  static LicenseValidationResult valid( LicensePayload payload ) {
    return new LicenseValidationResult( Status.VALID, payload, null, "License is valid" );
  }

  static LicenseValidationResult valid( Activation activation ) {
    return new LicenseValidationResult( Status.VALID, activation.getLicense(), activation,
      activation.isBound() ? "License is valid and activated for this machine" : "License is valid (unbound activation)" );
  }

  static LicenseValidationResult notInstalled( String where ) {
    return new LicenseValidationResult( Status.NOT_INSTALLED, null, null, "No license is installed (" + where + ")" );
  }

  static LicenseValidationResult notActivated( LicensePayload payload ) {
    return new LicenseValidationResult( Status.NOT_ACTIVATED, payload, null,
      "This license key has not been activated for this machine" );
  }

  static LicenseValidationResult wrongMachine( Activation activation, String why ) {
    return new LicenseValidationResult( Status.WRONG_MACHINE, activation.getLicense(), activation,
      "This license was activated for another machine (this one has " + why + ")" );
  }

  static LicenseValidationResult invalidSignature() {
    return new LicenseValidationResult( Status.INVALID_SIGNATURE, null, null,
      "License signature does not match — it was not issued by Hitachi Vantara, or has been altered" );
  }

  static LicenseValidationResult expired( LicensePayload payload ) {
    return new LicenseValidationResult( Status.EXPIRED, payload, null, "License expired" );
  }

  static LicenseValidationResult clockRollback( LicensePayload payload ) {
    return new LicenseValidationResult( Status.CLOCK_ROLLBACK, payload, null,
      "The system clock is earlier than a time this license has already been used at; correct the clock" );
  }

  static LicenseValidationResult malformed( String reason ) {
    return new LicenseValidationResult( Status.MALFORMED, null, null, "License is invalid: " + reason );
  }

  public boolean isValid() {
    return status == Status.VALID;
  }

  public Status getStatus() {
    return status;
  }

  /** The verified payload: present when valid, and for NOT_ACTIVATED, WRONG_MACHINE, EXPIRED and CLOCK_ROLLBACK. */
  public LicensePayload getPayload() {
    return payload;
  }

  /** The installed activation, when there is a genuine one (VALID or WRONG_MACHINE). */
  public Activation getActivation() {
    return activation;
  }

  public String getMessage() {
    return message;
  }

  @Override
  public String toString() {
    return "LicenseValidationResult{" + status + ": " + message + "}";
  }
}
