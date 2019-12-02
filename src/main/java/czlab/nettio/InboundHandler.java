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

import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.ReferenceCountUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelHandler;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInboundHandlerAdapter;

import static org.slf4j.LoggerFactory.getLogger;
import org.slf4j.Logger;
import czlab.basal.CU;

/**
 */
@ChannelHandler.Sharable
public abstract class InboundHandler extends ChannelInboundHandlerAdapter {

  public static final Logger TLOG = getLogger(InboundHandler.class);
  private boolean _rel;

  /**
   */
  protected InboundHandler(boolean rel) {
    _rel=rel;
  }
  protected InboundHandler() {
    this(false);
  }

  @Override
  public void channelActive(ChannelHandlerContext ctx) throws Exception {
    super.channelActive(ctx);
    onActive(ctx);
  }

  protected void onRead(ChannelHandlerContext ctx, Channel ch, Object msg) throws Exception {}
  protected void onHandlerAdded(ChannelHandlerContext ctx) throws Exception {}
  protected void onError(ChannelHandlerContext ctx, Throwable cause) throws Exception {}
  protected void onWriteChanged(ChannelHandlerContext ctx) throws Exception {}
  protected void onInactive(ChannelHandlerContext ctx) throws Exception {}
  protected void onActive(ChannelHandlerContext ctx) throws Exception {}
  protected void onUnreg(ChannelHandlerContext ctx) throws Exception {}
  protected void onReg(ChannelHandlerContext ctx) throws Exception {}

  @Override
  public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    super.channelInactive(ctx);
    onInactive(ctx);
  }

  @Override
  public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
    super.channelRegistered(ctx);
    onReg(ctx);
  }

  @Override
  public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
    super.channelUnregistered(ctx);
    onUnreg(ctx);
  }

  @Override
  public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
    super.channelWritabilityChanged(ctx);
    onWriteChanged(ctx);
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
    //super.exceptionCaught(ctx,cause);
    if (CU.canLog())
      TLOG.error("", cause);
    onError(ctx, cause);
    ctx.channel().close();
  }

  @Override
  public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
    if (CU.canLog())
      TLOG.debug("user-event-triggered: {}", evt != null ? evt : "null");
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    try {
      onRead(ctx, ctx.channel(), msg);
    } finally {
      if (_rel) ReferenceCountUtil.release(msg);
    }
  }

  @Override
  public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
    onHandlerAdded(ctx);
  }

}

