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

import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.netty.handler.codec.http.multipart.HttpDataFactory;
import io.netty.handler.codec.http.multipart.Attribute;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import static org.slf4j.LoggerFactory.getLogger;
import java.io.IOException;
import org.slf4j.Logger;
import czlab.jasal.XData;


/**
 * @author "Kenneth Leung"
 */
@SuppressWarnings("deprecation")
public abstract class WholeMessage implements HttpMessage {

  public static final Logger TLOG= getLogger(WholeMessage.class);
  protected HttpPostRequestDecoder _decoder;
  protected HttpRequest _attrOwner;
  protected HttpDataFactory _fac;
  protected HttpMessage _msg;
  protected Attribute _attr;
  protected XData _body;

  /**
   * @throws IOException
   */
  public void addContent(HttpContent c, boolean isLast) throws IOException {
    if (_attr != null) {
      _attr.addContent(c.content().retain(), isLast);
    }
    if (_decoder != null) {
      _decoder.offer(c);
    }
  }

  /**
   */
  protected abstract Object prepareBody(HttpDataFactory f, HttpRequest r);

  /**
   */
  protected abstract Object endContent(Object obj);

  /**
   */
  public abstract void init(HttpDataFactory f);

  /**
   */
  protected WholeMessage(HttpMessage m) {
    _body= new XData();
    _msg= m;
  }

  /**
   */
  public HttpMessage intern() { return _msg; }

  /**
   */
  public XData content() { return _body; }

  /**
   * @throws IOException
   */
  public void appendContent(HttpContent c, boolean isLast) throws IOException {
    if (_attr == null && _decoder == null) {
      throw new IllegalArgumentException("expecting no content");
    }
    addContent(c, isLast);
    if (! isLast) { return; }
    if (_attr != null) {
      _fac.removeHttpDataFromClean(_attrOwner,_attr);
      _body.reset(endContent(_attr));
      _attr.release();
      _attr=null;
    }
    if (_decoder != null) {
      _body.reset(endContent(_decoder));
      _decoder.destroy();
      _decoder=null;
    }
  }

  @Override
  public DecoderResult getDecoderResult() {
    return decoderResult();
  }

  @Override
  public DecoderResult decoderResult() {
    return _msg.decoderResult();
  }

  @Override
  public void setDecoderResult(DecoderResult arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public HttpVersion getProtocolVersion() {
    return protocolVersion();
  }

  @Override
  public HttpHeaders headers() {
    return _msg.headers();
  }

  @Override
  public HttpVersion protocolVersion() {
    return _msg.protocolVersion();
  }

  @Override
  public HttpMessage setProtocolVersion(HttpVersion arg0) {
    throw new UnsupportedOperationException();
  }

}


