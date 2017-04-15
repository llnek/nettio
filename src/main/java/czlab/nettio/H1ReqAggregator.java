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

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * @author "Kenneth Leung"
 */
public abstract class H1ReqAggregator extends H1Aggregator {

  @Override
  public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    super.channelInactive(ctx);
    onChannelInactive(ctx);
  }

  @Override
  public void channelActive(ChannelHandlerContext ctx) throws Exception {
    super.channelActive(ctx);
    onChannelActive(ctx);
  }

  /**
   */
  protected abstract void dequeue(ChannelHandlerContext ctx, Object msg);

  /**
   */
  protected abstract void onChannelInactive(ChannelHandlerContext ctx);

  /**
   */
  protected abstract void onChannelActive(ChannelHandlerContext ctx);

  /**
   */
  protected H1ReqAggregator() {}

  @Override
  public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise cp)
  throws Exception {
    super.write(ctx, msg, cp);
    if (msg instanceof FullHttpResponse) {
      FullHttpResponse r = (FullHttpResponse) msg;
      if (r.status() == HttpResponseStatus.CONTINUE) {
        return;
      }
    }
    dequeue(ctx,msg);
  }

}


