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

/**
 * @author Kenneth Leung
 *
 */
public abstract class H2ConnBuilder
  extends
  AbstractHttp2ConnectionHandlerBuilder<Http2ConnectionHandler, H2ConnBuilder> {

  private static Http2FrameLogger _log= new Http2FrameLogger(INFO, Http2ConnectionHandler.class);

  /**/
  protected H2ConnBuilder() {
    frameLogger(_log);
  }

  /**
   */
  public Http2ConnectionHandler newHandler(boolean asServer) {
    server(asServer);
    return super.build();
  }

  /**
   */
  public H2ConnBuilder setListener(Http2FrameListener f) {
    return super.frameListener(f);
  }

}


