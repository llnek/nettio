/**
 * Copyright © 2013-2019, Kenneth Leung. All rights reserved.
 * The use and distribution terms for this software are covered by the
 * Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 * which can be found in the file epl-v10.html at the root of this distribution.
 * By using this software in any fashion, you are agreeing to be bound by
 * the terms of this license.
 * You must not remove this notice, or any other, from this software.
 */
package czlab.niou;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mimic com.sun.net.httpserver.Headers, just in case openjdk
 * doesn't support it.
 *
 */
public class Headers implements Map<String, List<String>> {

  private Map<String, List<String>> _impl = new HashMap<String, List<String>>(32);

  private String lcase(Object s) {
    return ((String)s).toLowerCase();
  }

  public Headers() {
  }

  public boolean containsKey(Object kee) {
    return (kee instanceof String) ? _impl.containsKey(lcase(kee)) : false;
  }

  public boolean containsValue(Object v) {
    return _impl.containsValue(v);
  }

  public int size() {
    return _impl.size();
  }

  public boolean isEmpty() {
    return _impl.isEmpty();
  }

  public List<String> get(Object kee) {
    return (kee instanceof String) ? _impl.get(lcase(kee)) : null;
  }

  public String getFirst(String kee) {
    List<String> out = (kee instanceof String) ? _impl.get(lcase(kee)) : null;
    return (out != null) ? out.get(0) : null;
  }

  public List<String> put(String kee, List<String> vals) {
    return (kee != null) ? _impl.put(lcase(kee), vals) : null;
  }

  public Headers add(String kee, String val) {
    if (kee != null && val != null) {
      String k = lcase(kee);
      List<String> vals = _impl.get(k);
      if (vals == null) {
        vals = new LinkedList<String>();
        _impl.put(k, vals);
      }
      vals.add(val);
    }
    return this;
  }

  @SuppressWarnings("serial")
  public void set(String kee, String val) {
    if (kee != null && val != null) {
      put(lcase(kee), new LinkedList<String>() {{ add(val); }} );
    }
  }

  public List<String> remove(Object kee) {
    return (kee instanceof String) ? _impl.remove(lcase(kee)) : null;
  }

  public void putAll(Map<? extends String, ? extends List<String>> other) {
    if (other != null) {
      _impl.putAll(other);
    }
  }

  public boolean equals(Object other) {
    return _impl.equals(other);
  }

  public int hashCode() {
    return _impl.hashCode();
  }

  public void clear() {
    _impl.clear();
  }

  public Set<String> keySet() {
    return _impl.keySet();
  }

  public Collection<List<String>> values() {
    return _impl.values();
  }

  public Set<Map.Entry<String, List<String>>> entrySet() {
    return _impl.entrySet();
  }

}

