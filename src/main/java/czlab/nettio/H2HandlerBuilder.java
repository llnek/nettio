/**
 * Copyright © 2013-2019, Kenneth Leung. All rights reserved.
 * The use and distribution terms for this software are covered by the
 * Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 * which can be found in the file epl-v10.html at the root of this distribution.
 * By using this software in any fashion, you are agreeing to be bound by
 * the terms of this license.
 * You must not remove this notice, or any other, from this software.
 */

package czlab.nettio;

import io.netty.handler.codec.http2.AbstractHttp2ConnectionHandlerBuilder;
import static io.netty.handler.logging.LogLevel.INFO;
import io.netty.handler.codec.http2.Http2FrameListener;
import io.netty.handler.codec.http2.Http2FrameLogger;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.Http2ConnectionDecoder;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;

/**
 * @author Kenneth Leung
 *
 */
public class H2HandlerBuilder
  extends AbstractHttp2ConnectionHandlerBuilder<H2Handler, H2HandlerBuilder> {

  private static final Http2FrameLogger logger = new Http2FrameLogger(INFO, H2Handler.class);

  protected H2HandlerBuilder() {
    frameLogger(logger);
  }

  @Override
  public H2Handler build() {
    return super.build();
  }

  @Override
  protected H2Handler build(Http2ConnectionDecoder decoder,
                            Http2ConnectionEncoder encoder,
                            Http2Settings settings) {
    return null;
  }

}

