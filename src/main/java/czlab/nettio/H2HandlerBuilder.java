/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Copyright © 2013-2024, Kenneth Leung. All rights reserved. */

package czlab.nettio;

import io.netty.handler.codec.http2.AbstractHttp2ConnectionHandlerBuilder;
import static io.netty.handler.logging.LogLevel.INFO;
import static io.netty.handler.logging.LogLevel.DEBUG;
import io.netty.handler.codec.http2.Http2FrameListener;
import io.netty.handler.codec.http2.Http2FrameLogger;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.Http2ConnectionDecoder;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;

/**
 *
 */
public abstract class H2HandlerBuilder
  extends AbstractHttp2ConnectionHandlerBuilder<H2Handler, H2HandlerBuilder> {

  private static final Http2FrameLogger logger = new Http2FrameLogger(INFO, H2Handler.class);

  protected H2HandlerBuilder(Http2Connection c) {
    frameLogger(logger);
    connection(c);
  }

  public H2Handler buildEx() {
    return super.build();
  }

  @Override
  protected H2Handler build(Http2ConnectionDecoder decoder,
                            Http2ConnectionEncoder encoder,
                            Http2Settings settings) throws Exception {
    H2Handler h = newHandler(decoder,encoder,settings);
    frameListener(h);
    return h;
  }

  protected abstract H2Handler newHandler(Http2ConnectionDecoder decoder,
                            Http2ConnectionEncoder encoder,
                            Http2Settings settings) throws Exception;

}


