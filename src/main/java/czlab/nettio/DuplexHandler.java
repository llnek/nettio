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

import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.ReferenceCountUtil;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.ChannelHandler;
import io.netty.channel.Channel;
import static org.slf4j.LoggerFactory.getLogger;
import org.slf4j.Logger;
import czlab.basal.CU;

/**
 */
@ChannelHandler.Sharable
public abstract class DuplexHandler extends ChannelDuplexHandler {

  public static final Logger TLOG = getLogger(DuplexHandler.class);
  private boolean _rel;

  /**
   */
  protected DuplexHandler(boolean rel) {
    _rel=rel;
  }
  protected DuplexHandler() {
    this(false);
  }

  @Override
  public void channelActive(ChannelHandlerContext ctx) throws Exception {
    super.channelActive(ctx);
    onActive(ctx);
  }

  protected void onWrite(ChannelHandlerContext ctx, Object msg, ChannelPromise cp) throws Exception {}
  protected void onHandlerAdded(ChannelHandlerContext ctx) throws Exception {}
  protected void onRead(ChannelHandlerContext ctx, Channel ch, Object msg) throws Exception {}
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

  protected Object preWrite(ChannelHandlerContext ctx, Object msg) throws Exception {
    return msg;
  }

  @Override
  public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise cp) throws Exception {
    if (msg==null) {
      // should never be null, right?
      super.write(ctx, msg, cp);
      onWrite(ctx, msg, cp);
    } else {
      Object msg_ = preWrite(ctx, msg);
      if (msg_ != null) {
        super.write(ctx, msg_, cp);
        onWrite(ctx, msg_, cp);
      }
    }
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
    //super.exceptionCaught(ctx,cause);
    if (CU.canLog())
      TLOG.error("", cause);
    onError(ctx,cause);
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

