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
import io.netty.channel.ChannelPipeline;
/**
 * @author Kenneth Leung
 */
public abstract class ChannelInizer extends io.netty.channel.ChannelInitializer {

  /*
   */
  protected ChannelInizer() {
    //super();
  }

  @Override
  protected void initChannel(Channel ch) throws Exception {
    onInitChannel(ch, ch.pipeline());
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
    try { onError(ctx,cause); } catch (Throwable t) {}
    super.exceptionCaught(ctx, cause);
  }

  @Override
  public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
    super.handlerAdded(ctx);
    try { onHandlerAdded(ctx); } catch (Throwable t) {}
  }

  protected void onError(ChannelHandlerContext ctx, Throwable cause) {}
  protected void onHandlerAdded(ChannelHandlerContext ctx) {}

  protected abstract void onInitChannel(Channel c, ChannelPipeline p);

}

