/**
 * Copyright © 2013-2020, Kenneth Leung. All rights reserved.
 * The use and distribution terms for this software are covered by the
 * Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 * which can be found in the file epl-v10.html at the root of this distribution.
 * By using this software in any fashion, you are agreeing to be bound by
 * the terms of this license.
 * You must not remove this notice, or any other, from this software.
 */

package czlab.nettio;

import static org.slf4j.LoggerFactory.getLogger;
import org.slf4j.Logger;
import czlab.basal.CU;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelPromise;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.util.AttributeKey;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;

/**
 * Refer to http://www.adobe.com/devnet/articles/crossdomain_policy_file_spec.html
 *
 */
public class FlashHandler extends InboundHandler {

  public static final Logger TLOG = getLogger(FlashHandler.class);

  private static final String XML=
    "<?xml version=\"1.0\"?>\r\n" +
    "<!DOCTYPE cross-domain-policy SYSTEM \"/xml/dtds/cross-domain-policy.dtd\">\r\n" +
    "<cross-domain-policy>\r\n" +
    "  <site-control permitted-cross-domain-policies=\"master-only\"/>\r\n" +
    "  <allow-access-from domain=\"*\" to-ports=\"" +
    "*" +
    "\" />\r\n" +
    "</cross-domain-policy>\r\n";

  private static final AttributeKey<Integer> HINT = AttributeKey.newInstance("flash-hint");
  private static final String FLASH_POLICY_REQ = "<policy-file-request/>";

  private static final char[] FLASH_CHS = FLASH_POLICY_REQ.toCharArray();
  private static final int FLASH_LEN = FLASH_CHS.length;

  public static final String NAME = FlashHandler.class.getSimpleName();
  public static final FlashHandler shared = new FlashHandler();

  /**/
  public static ChannelPipeline addBefore(ChannelPipeline pipe, String name) {
    pipe.addBefore(name, NAME, shared);
    return pipe;
  }

  /**/
  public static ChannelPipeline addLast(ChannelPipeline pipe) {
    pipe.addLast(NAME, shared);
    return pipe;
  }

  /**/
  protected FlashHandler() {}

  @Override
  public void onRead(ChannelHandlerContext ctx, Channel ch, Object msg) throws Exception {
    ByteBuf bmsg = (msg instanceof ByteBuf) ? (ByteBuf)msg : null;

    if (CU.canLog())
      TLOG.debug("FlashHandler:channelRead called");

    if (bmsg == null || !bmsg.isReadable()) {
      return;
    }

    Integer hint = (Integer) ch.attr(HINT).get();
    ByteBuf bbuf= bmsg.copy();
    //first byte?
    if (hint==null) { hint= Integer.valueOf(0); }
    int num= bbuf.readableBytes();
    int pos= bbuf.readerIndex();
    int state= -1;
    int c, nn;

    for (int i =0; i < num; ++i) {
      nn = bbuf.getUnsignedByte(pos + i);
      c= (int) FLASH_CHS[hint];
      if (c == nn) {
        hint += 1;
        if (hint == FLASH_LEN) {
          //matched!
          finito(ctx, ch, msg, true);
          state= 1;
          break;
        }
      } else {
        finito(ctx, ch, msg, false);
        state= 0;
        break;
      }
    }

    if (state != 0) { ReferenceCountUtil.release(msg); }
    ReferenceCountUtil.release(bbuf);

    // not done testing yet...
    if (state < 0 && hint < FLASH_LEN) {
      ch.attr(HINT).set(hint);
    }
  }

  /**/
  private void finito(ChannelHandlerContext ctx,
      Channel ch,
      Object msg, boolean success) {

    ch.attr(HINT).set(null);

    if (success) {
      if (CU.canLog())
        TLOG.debug("FlashHandler: reply back to client with policy info");
      ByteBuf b= ctx.alloc().directBuffer();
      b.writeCharSequence(XML, CharsetUtil.US_ASCII);
      ctx.writeAndFlush(b).addListener(ChannelFutureListener.CLOSE);
    } else {
      if (CU.canLog())
        TLOG.debug("FlashHandler: removing self. finito!");
      ctx.pipeline().remove(this);
      ctx.fireChannelRead(msg);
    }
  }

}


