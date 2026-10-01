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

package org.eclipse.jetty.util;

import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Compatibility copy of Jetty 9's {@code ConcurrentHashSet}, which Jetty 10 removed.
 * <p>
 * PDI moved Carte to Jetty 12, but two prebuilt Pentaho libraries in lib/ still create this class
 * (pentaho-metaverse-api's ExternalResourceCache and pentaho-platform-extensions' settings Service). Remove it once
 * they are rebuilt with {@code ConcurrentHashMap.newKeySet()}.
 */
public class ConcurrentHashSet<E> extends AbstractSet<E> implements Set<E> {

  private final Set<E> set = ConcurrentHashMap.newKeySet();

  @Override
  public boolean add( E e ) {
    return set.add( e );
  }

  @Override
  public void clear() {
    set.clear();
  }

  @Override
  public boolean contains( Object o ) {
    return set.contains( o );
  }

  @Override
  public boolean isEmpty() {
    return set.isEmpty();
  }

  @Override
  public Iterator<E> iterator() {
    return set.iterator();
  }

  @Override
  public boolean remove( Object o ) {
    return set.remove( o );
  }

  @Override
  public int size() {
    return set.size();
  }
}
