
package czlab.nettio;

import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.ReferenceCountUtil;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

import static org.slf4j.LoggerFactory.getLogger;
import org.slf4j.Logger;

/**
 * @author Kenneth Leung
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

  /**
   */
  protected DuplexHandler() {
    this(false);
  }

  @Override
  public void channelActive(ChannelHandlerContext ctx) throws Exception {
    super.channelActive(ctx);
    onActive(ctx);
  }

  protected void onWrite(ChannelHandlerContext ctx, Object msg, ChannelPromise cp) throws Exception {}
  protected void onWriteChanged(ChannelHandlerContext ctx) throws Exception {}
  protected void onRead(ChannelHandlerContext ctx, Object m) throws Exception {}
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
  public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise cp) throws Exception {
    super.write(ctx, msg, cp);
    onWrite(ctx, msg, cp);
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
    TLOG.error("", cause);
    ctx.channel().close();
  }

  @Override
  public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
    TLOG.debug("user-event-triggered: {}", evt != null ? evt : "null");
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    try {
      onRead(ctx, msg);
    } catch (Exception e) {
    } finally {
      if (_rel) ReferenceCountUtil.release(msg);
    }
  }

}

