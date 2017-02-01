/**
 * Copyright (c) 2013-2017, Kenneth Leung. All rights reserved.
 * The use and distribution terms for this software are covered by the
 * Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 * which can be found in the file epl-v10.html at the root of this distribution.
 * By using this software in any fashion, you are agreeing to be bound by
 * the terms of this license.
 * You must not remove this notice, or any other, from this software.
 */

package czlab.convoy.nettio;

import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import static org.slf4j.LoggerFactory.getLogger;
import org.slf4j.Logger;


/**
 * @author Kenneth Leung
 */
@ChannelHandler.Sharable
public abstract class InboundHandler extends SimpleChannelInboundHandler<Object> {

  public static final Logger TLOG = getLogger(InboundHandler.class);

  @Override
  public void  userEventTriggered(ChannelHandlerContext ctx, Object evt)
  throws Exception {
    TLOG.debug("user-event-triggered: {}", evt != null ? evt : "null");
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext c, Throwable e)
  throws Exception {
    TLOG.error("", e);
    c.channel().close();
  }

  /**/
  protected InboundHandler() {}

}


