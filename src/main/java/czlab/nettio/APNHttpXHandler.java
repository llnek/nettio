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

import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;

/**
 */
@ChannelHandler.Sharable
public abstract class APNHttpXHandler
  extends ApplicationProtocolNegotiationHandler {

  public APNHttpXHandler() {
    super(ApplicationProtocolNames.HTTP_1_1);
  }

  @Override
  protected void configurePipeline(ChannelHandlerContext ctx, String protocol)
  throws Exception {
    //System.out.println("APNHttpXHandler: configPipeline: protocol = " + protocol);
    if (ApplicationProtocolNames.HTTP_2.equals(protocol)) {
      cfgH2(ctx.pipeline());
    } else if (ApplicationProtocolNames.HTTP_1_1.equals(protocol)) {
      cfgH1(ctx.pipeline());
    } else {
      throw new IllegalStateException("Unknown protocol: " + protocol);
    }
  }

  protected abstract void cfgH2(ChannelPipeline p) throws Exception;
  protected abstract void cfgH1(ChannelPipeline p) throws Exception;

}

