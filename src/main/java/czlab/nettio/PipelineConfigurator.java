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

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;

import static org.slf4j.LoggerFactory.getLogger;
import org.slf4j.Logger;
import czlab.basal.CU;

/**
 */
public abstract class PipelineConfigurator extends ChannelInitializer {

  public static final Logger TLOG = getLogger(PipelineConfigurator.class);

  /*
   */
  protected PipelineConfigurator() {
    //super();
  }

  @Override
  protected void initChannel(Channel ch) throws Exception {
    onInitChannel(ch.pipeline());
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
    super.exceptionCaught(ctx, cause);
    if (CU.canLog())
      TLOG.error("", cause);
    try { onError(ctx,cause); } catch (Throwable t) {}
  }

  @Override
  public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
    super.handlerAdded(ctx);
    try { onHandlerAdded(ctx); } catch (Throwable t) {}
  }

  protected void onError(ChannelHandlerContext ctx, Throwable cause) {}
  protected void onHandlerAdded(ChannelHandlerContext ctx) {}

  protected abstract void onInitChannel(ChannelPipeline p);

}

