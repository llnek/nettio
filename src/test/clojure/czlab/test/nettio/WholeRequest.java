/**
 * Copyright (c) 2013-2017, Kenneth Leung. All rights reserved.
 * The use and distribution terms for this software are covered by the
 * Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 * which can be found in the file epl-v10.html at the root of this distribution.
 * By using this software in any fashion, you are agreeing to be bound by
 * the terms of this license.
 * You must not remove this notice, or any other, from this software.
 */

package czlab.nettio;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.multipart.Attribute;
import io.netty.handler.codec.http.multipart.HttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;

/**
 * @author "Kenneth Leung"
 */
@SuppressWarnings("deprecation")
public abstract class WholeRequest
  extends WholeMessage implements HttpRequest {

  /**
   */
  protected WholeRequest(HttpRequest m) {
    super(m);
    _attrOwner=m;
  }

  @Override
  public void init(HttpDataFactory f) {
    Object obj= prepareBody(_fac=f, _attrOwner);
    if (obj instanceof HttpPostRequestDecoder) {
      _decoder= (HttpPostRequestDecoder) obj;
    }
    else if (obj instanceof Attribute) {
      _attr= (Attribute)obj;
    }
    else if (obj != null) {
      throw new IllegalArgumentException("Bad body part");
    }
  }

  /**
   */
  private HttpRequest req() { return (HttpRequest) _msg; }

  @Override
  public HttpMethod getMethod() {
    return method();
  }

  @Override
  public String getUri() {
    return uri();
  }

  @Override
  public HttpMethod method() {
    String x= req().headers().get("X-HTTP-Method-Override");
    return (x != null && x.length() > 0)
      ? HttpMethod.valueOf(x) : req().method();
  }

  @Override
  public HttpRequest setProtocolVersion(HttpVersion arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public HttpRequest setMethod(HttpMethod arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public HttpRequest setUri(String arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public String uri() {
    return req().uri();
  }


}


