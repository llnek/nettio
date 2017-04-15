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

import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static org.slf4j.LoggerFactory.getLogger;
import org.slf4j.Logger;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpServerUpgradeHandler;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2ConnectionDecoder;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2Flags;
import io.netty.handler.codec.http2.Http2FrameListener;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.util.AsciiString;


/**
 *
 */
public abstract class H2Connector
  extends Http2ConnectionHandler implements Http2FrameListener {

  private static final String UPGRADE_HEADER = "http-to-http2-upgrade";
  public static final Logger TLOG = getLogger(H2Connector.class);

  /**/
  public H2Connector(Http2ConnectionDecoder decoder,
      Http2ConnectionEncoder encoder,
      Http2Settings initialSettings) {
    super(decoder, encoder, initialSettings);
  }

  /**
   * Handles the cleartext HTTP upgrade event. If an upgrade occurred, sends a simple response via HTTP/2
   * on stream 1 (the stream specifically reserved for cleartext HTTP upgrade).
   */
  @Override
  public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {

    if (evt instanceof HttpServerUpgradeHandler.UpgradeEvent) {
      Http2Headers h=
        new DefaultHttp2Headers().
        status(OK.codeAsText()).
        set(new AsciiString(UPGRADE_HEADER),
            new AsciiString("true"));
      encoder().writeHeaders(ctx, 1, h, 0, true, ctx.newPromise());
    }

    super.userEventTriggered(ctx, evt);
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
    super.exceptionCaught(ctx, cause);
    TLOG.error("", cause);
    ctx.close();
  }

  @Override
  public int onDataRead(ChannelHandlerContext ctx, int streamId, ByteBuf data, int padding, boolean endOfStream) {
    int processed = data.readableBytes() + padding;
    if (endOfStream) {
      onDataRead(ctx, streamId, data);
    }
    return processed;
  }

  /**
   */
  protected abstract void onHeadersRead(ChannelHandlerContext ctx, int streamId, Http2Headers headers);

  /**
   */
  protected abstract void onDataRead(ChannelHandlerContext ctx, int streamId, ByteBuf data);

  /**
   */
  @SuppressWarnings("unused")
  private void sendResponse(ChannelHandlerContext ctx, int streamId, ByteBuf payload) {
    Http2Headers h= new DefaultHttp2Headers().status(OK.codeAsText());
    encoder().writeHeaders(ctx, streamId, h, 0, false, ctx.newPromise());
    encoder().writeData(ctx, streamId, payload, 0, true, ctx.newPromise());
    ctx.flush();
  }

  @Override
  public void onHeadersRead(ChannelHandlerContext ctx, int streamId,
    Http2Headers headers, int padding, boolean endOfStream) {
    if (endOfStream) {
      onHeadersRead(ctx, streamId, headers);
      //ByteBuf content = ctx.alloc().buffer();
      //content.writeBytes(RESPONSE_BYTES.duplicate());
      //ByteBufUtil.writeAscii(content, " - via HTTP/2");
      //sendResponse(ctx, streamId, content);
    }
  }

  @Override
  public void onHeadersRead(
      ChannelHandlerContext ctx, int streamId,
      Http2Headers headers,
      int streamDependency,
      short weight,
      boolean exclusive, int padding, boolean endOfStream) {
    onHeadersRead(ctx, streamId, headers, padding, endOfStream);
  }

  @Override
  public void onPriorityRead(ChannelHandlerContext ctx,
      int streamId, int streamDependency,
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
  public void onPingRead(ChannelHandlerContext ctx, ByteBuf data) {
  }

  @Override
  public void onPingAckRead(ChannelHandlerContext ctx, ByteBuf data) {
  }

  @Override
  public void onPushPromiseRead(ChannelHandlerContext ctx, int streamId, int promisedStreamId, Http2Headers headers, int padding) {
  }

  @Override
  public void onGoAwayRead(ChannelHandlerContext ctx, int lastStreamId, long errorCode, ByteBuf debugData) {
  }

  @Override
  public void onWindowUpdateRead(ChannelHandlerContext ctx, int streamId, int windowSizeIncrement) {
  }

  @Override
  public void onUnknownFrame(ChannelHandlerContext ctx, byte frameType, int streamId, Http2Flags flags, ByteBuf payload) {

  }

}


