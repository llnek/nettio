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

import io.netty.handler.codec.http2.AbstractHttp2ConnectionHandlerBuilder;
import static io.netty.handler.logging.LogLevel.INFO;
import io.netty.handler.codec.http2.Http2FrameListener;
import io.netty.handler.codec.http2.Http2FrameLogger;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelPromise;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpScheme;
import io.netty.handler.codec.http.HttpServerUpgradeHandler;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2ConnectionDecoder;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2Flags;
import io.netty.handler.codec.http2.Http2FrameListener;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.util.CharsetUtil;
import static io.netty.buffer.Unpooled.copiedBuffer;
import static io.netty.buffer.Unpooled.unreleasableBuffer;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static org.slf4j.LoggerFactory.getLogger;
import org.slf4j.Logger;
import czlab.basal.CU;

/**
 *
 */
public abstract class H2Handler extends Http2ConnectionHandler implements Http2FrameListener {

  public static final Logger TLOG = getLogger(ChannelInizer.class);

  public H2Handler(Http2ConnectionDecoder decoder, Http2ConnectionEncoder encoder,
                   Http2Settings initialSettings) {
    super(decoder, encoder, initialSettings);
  }

  public void onError(ChannelHandlerContext ctx, boolean outbound, Throwable cause) {
    super.onError(ctx, outbound, cause);
    if (CU.canLog())
      TLOG.error("", cause);
  }

  public void parWrite(ChannelHandlerContext ctx,
      Object msg, ChannelPromise promise) throws java.lang.Exception {
    super.write(ctx, msg, promise);
  }

  @Override
  public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
    super.userEventTriggered(ctx, evt);
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
    super.exceptionCaught(ctx, cause);
    if (CU.canLog())
      TLOG.error("", cause);
    ctx.close();
  }

  @Override
  public int onDataRead(ChannelHandlerContext ctx, int streamId, ByteBuf data, int padding, boolean endOfStream) {
    int processed = data.readableBytes() + padding;
    onData(ctx, streamId, data, padding, endOfStream);
    return processed;
  }

  protected abstract void onData(ChannelHandlerContext ctx, int streamId,
      ByteBuf data, int padding, boolean endOfStream);

  protected abstract void onHeaders(ChannelHandlerContext ctx, int streamId,
                            Http2Headers headers, int padding, boolean endOfStream);

  @Override
  public void onHeadersRead(ChannelHandlerContext ctx, int streamId,
                            Http2Headers headers, int padding, boolean endOfStream) {
    onHeaders(ctx, streamId, headers, padding, endOfStream);
  }

  @Override
  public void onHeadersRead(ChannelHandlerContext ctx, int streamId, Http2Headers headers, int streamDependency,
                            short weight, boolean exclusive, int padding, boolean endOfStream) {
    onHeaders(ctx, streamId, headers, padding, endOfStream);
  }

  @Override
  public void onPriorityRead(ChannelHandlerContext ctx, int streamId, int streamDependency,
                             short weight, boolean exclusive) {
  }

  @Override
  public void onRstStreamRead(ChannelHandlerContext ctx, int streamId, long errorCode) {
  }

  @Override
  public void onSettingsAckRead(ChannelHandlerContext ctx) {
  }

  @Override
  public void onSettingsRead(ChannelHandlerContext ctx, Http2Settings settings) {
  }

  @Override
  public void onPingRead(ChannelHandlerContext ctx, long data) {
  }

  @Override
  public void onPingAckRead(ChannelHandlerContext ctx, long data) {
  }

  @Override
  public void onPushPromiseRead(ChannelHandlerContext ctx, int streamId, int promisedStreamId,
                                Http2Headers headers, int padding) {
  }

  @Override
  public void onGoAwayRead(ChannelHandlerContext ctx, int lastStreamId, long errorCode, ByteBuf debugData) {
  }

  @Override
  public void onWindowUpdateRead(ChannelHandlerContext ctx, int streamId, int windowSizeIncrement) {
  }

  @Override
  public void onUnknownFrame(ChannelHandlerContext ctx, byte frameType, int streamId,
                             Http2Flags flags, ByteBuf payload) {
  }

}


