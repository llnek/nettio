/**
 * Copyright (c) 2013-2017, Kenneth Leung. All rights reserved.
 * The use and distribution terms for this software are covered by the
 * Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 * which can be found in the file epl-v10.html at the root of this distribution.
 * By using this software in any fashion, you are agreeing to be bound by
 * the terms of this license.
 * You must not remove this notice, or any other, from this software.
 */

package czlab.convoy.nettio;

import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.multipart.Attribute;
import io.netty.handler.codec.http.multipart.HttpDataFactory;


/**
 * @author "Kenneth Leung"
 */
@SuppressWarnings("deprecation")
public abstract class WholeResponse
  extends WholeMessage implements HttpResponse {

  /**
   */
  private HttpResponse rsp() { return (HttpResponse) _msg; }

  /**
   */
  protected WholeResponse(HttpResponse m) {
    super(m);
  }

  @Override
  public void init(HttpDataFactory f) {
    Object obj= prepareBody(_fac=f,
                            _attrOwner=fakeit(_msg));
    if (obj instanceof Attribute) {
      _attr= (Attribute)obj;
    }
    else if (obj != null) {
      throw new IllegalArgumentException("Bad body part");
    }
  }

  /**
   */
  private HttpRequest fakeit(HttpMessage msg) {
    return new DefaultHttpRequest(
        msg.protocolVersion(),
        HttpMethod.POST,
        "/",
        msg.headers());
  }

  @Override
  public HttpResponseStatus getStatus() {
    return status();
  }

  @Override
  public HttpResponse setProtocolVersion(HttpVersion arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public HttpResponse setStatus(HttpResponseStatus arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public HttpResponseStatus status() {
    return rsp().status();
  }

}


