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

import io.netty.handler.codec.http2.AbstractHttp2ConnectionHandlerBuilder;
import static io.netty.handler.logging.LogLevel.INFO;
import io.netty.handler.codec.http2.Http2FrameListener;
import io.netty.handler.codec.http2.Http2FrameLogger;

/**
 * @author Kenneth Leung
 *
 */
public abstract class H2Builder
  extends
  AbstractHttp2ConnectionHandlerBuilder<H2Connector, H2Builder> {

  private static Http2FrameLogger _log= new Http2FrameLogger(INFO, H2Connector.class);

  /**/
  protected H2Builder() {
    frameLogger(_log);
  }

  /**/
  public H2Connector build() {
    return super.build();
  }

  @Override
  public H2Builder frameListener(Http2FrameListener f) {
    return super.frameListener(f);
  }

}



