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

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.Channel;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.InboundHttp2ToHttpAdapter;


/**
 * @author Kenneth Leung
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

