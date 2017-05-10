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

import czlab.jasal.Disposable;
import io.netty.channel.Channel;

/**
 * @author Kenneth Leung
 */
public interface ClientConnect extends Disposable {

  /**
   */
  public Channel channel();

  /**
   */
  public String host();

  /**
   */
  public int port();

  /**
   */
  public void await(long millis);
}




