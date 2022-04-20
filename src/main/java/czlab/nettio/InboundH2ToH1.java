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
 * Copyright © 2013-2022, Kenneth Leung. All rights reserved. */

package czlab.nettio;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.Channel;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.InboundHttp2ToHttpAdapter;


/**
 */
//@ChannelHandler.Sharable
public class InboundH2ToH1 extends InboundHttp2ToHttpAdapter {

  /**
   *
   */
  public InboundH2ToH1(Http2Connection conn, int maxLength,
                       boolean validateHeaders, boolean propagateSettings) {
    super(conn,maxLength,validateHeaders,propagateSettings);
  }

  @Override
  public void onSettingsRead(ChannelHandlerContext ctx, Http2Settings settings) throws Http2Exception {
    super.onSettingsRead(ctx,settings);
    onSettings(ctx, ctx.channel(), settings);
  }

  protected void onSettings(ChannelHandlerContext ctx, Channel c, Http2Settings settings) {}

}

