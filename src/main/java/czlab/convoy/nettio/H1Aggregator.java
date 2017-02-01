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

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author "Kenneth Leung"
 */
@ChannelHandler.Sharable
public abstract class H1Aggregator extends ChannelDuplexHandler {

  /**
   */
  public static final Logger TLOG = LoggerFactory.getLogger(H1Aggregator.class);

  /**
   */
  protected H1Aggregator() {}

}


