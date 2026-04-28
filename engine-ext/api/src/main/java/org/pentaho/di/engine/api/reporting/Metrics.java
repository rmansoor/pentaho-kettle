/*! ******************************************************************************
 *
 * Pentaho
 *
 * Copyright (C) 2024 by Hitachi Vantara, LLC : http://www.pentaho.com
 *
 * Use of this software is governed by the Business Source License included
 * in the LICENSE.TXT file.
 *
 * Change Date: 2029-07-20
 ******************************************************************************/


package org.pentaho.di.engine.api.reporting;

import java.io.Serializable;

/**
 * Created by hudak on 1/5/17.
 */
public class Metrics implements Serializable {
  private static final long serialVersionUID = -5354823227842967351L;
  private final long in, out, dropped, inFlight;
  private final long runtime;
  private final long startTimeMs;
  private final long endTimeMs;

  public static Metrics empty() {
    return new Metrics( 0, 0, 0, 0, 0, 0, 0 );
  }

  public Metrics( long in, long out, long dropped, long inFlight ) {
    this( in, out, dropped, inFlight, 0, 0, 0 );
  }

  public Metrics( long in, long out, long dropped, long inFlight, long runtime, long startTimeMs, long endTimeMs ) {
    this.in = in;
    this.out = out;
    this.dropped = dropped;
    this.inFlight = inFlight;
    this.runtime = runtime;
    this.startTimeMs = startTimeMs;
    this.endTimeMs = endTimeMs;
  }

  /**
   * Get number of {@link PDIEvent}s into this component
   *
   * @return
   */
  public long getIn() {
    return in;
  }

  /**
   * Get number of {@link PDIEvent}s out from this component
   *
   * @return
   */
  public long getOut() {
    return out;
  }

  /**
   * Get number of {@link PDIEvent}s dropped (errorred)
   *
   * @return
   */
  public long getDropped() {
    return dropped;
  }

  /**
   * Get number of {@link PDIEvent}s currently in-flight
   *
   * @return
   */
  public long getInFlight() {
    return inFlight;
  }

  /**
   * Get execution time in milliseconds
   *
   * @return execution time in ms, or 0 if not completed
   */
  public long getRuntime() {
    return runtime;
  }

  /**
   * Get operation start time (timestamp in milliseconds)
   *
   * @return start time ms, or 0 if not started
   */
  public long getStartTimeMs() {
    return startTimeMs;
  }

  /**
   * Get operation end time (timestamp in milliseconds)
   *
   * @return end time ms, or 0 if not completed
   */
  public long getEndTimeMs() {
    return endTimeMs;
  }

  @Override public String toString() {
    return String.format( "Metrics{in=%d, out=%d, dropped=%d, inFlight=%d, runtime=%d}", 
      in, out, dropped, inFlight, runtime );
  }

  public Metrics add( Metrics right ) {
    return new Metrics(
      getIn() + right.getIn(),
      getOut() + right.getOut(),
      getDropped() + right.getDropped(),
      getInFlight() + right.getInFlight(),
      // For execution time, take the maximum
      Math.max( getRuntime(), right.getRuntime() ),
      // For start time, take the earliest (minimum non-zero)
      this.startTimeMs > 0 && right.startTimeMs > 0 ? 
        Math.min( getStartTimeMs(), right.getStartTimeMs() ) :
        Math.max( getStartTimeMs(), right.getStartTimeMs() ),
      // For end time, take the latest (maximum)
      Math.max( getEndTimeMs(), right.getEndTimeMs() )
    );
  }

  @Override public boolean equals( Object o ) {
    if ( this == o ) {
      return true;
    }
    if ( !( o instanceof Metrics ) ) {
      return false;
    }

    Metrics metrics = (Metrics) o;

    if ( in != metrics.in ) {
      return false;
    }
    if ( out != metrics.out ) {
      return false;
    }
    if ( dropped != metrics.dropped ) {
      return false;
    }
    if ( inFlight != metrics.inFlight ) {
      return false;
    }
    if ( runtime != metrics.runtime ) {
      return false;
    }
    if ( startTimeMs != metrics.startTimeMs ) {
      return false;
    }
    return endTimeMs == metrics.endTimeMs;
  }

  @Override public int hashCode() {
    int result = (int) ( in ^ ( in >>> 32 ) );
    result = 31 * result + (int) ( out ^ ( out >>> 32 ) );
    result = 31 * result + (int) ( dropped ^ ( dropped >>> 32 ) );
    result = 31 * result + (int) ( inFlight ^ ( inFlight >>> 32 ) );
    result = 31 * result + (int) ( runtime ^ ( runtime >>> 32 ) );
    result = 31 * result + (int) ( startTimeMs ^ ( startTimeMs >>> 32 ) );
    result = 31 * result + (int) ( endTimeMs ^ ( endTimeMs >>> 32 ) );
    return result;
  }
}
