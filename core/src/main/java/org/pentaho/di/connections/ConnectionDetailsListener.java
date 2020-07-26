package org.pentaho.di.connections;

public interface ConnectionDetailsListener {

  public void connectionUpdated( ConnectionDetails connectionDetails );

  public void connectionAdded( ConnectionDetails connectionDetails );

}


