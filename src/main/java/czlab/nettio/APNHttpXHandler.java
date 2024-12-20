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
 * Copyright © 2013-2024, Kenneth Leung. All rights reserved. */

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

