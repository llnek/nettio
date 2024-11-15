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
//Lifted from netty - Http2CodecUtil.SimpleChannelPromiseAggregator.
//The constructor is package protected and can't be accessed.
//Hence this clone!
//
package czlab.nettio;

import io.netty.channel.Channel;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelPromise;
import io.netty.util.concurrent.EventExecutor;

/**
 * Provides the ability to associate the outcome of multiple {@link ChannelPromise}
 * objects into a single {@link ChannelPromise} object.
 */
public class CPAggregator extends DefaultChannelPromise {

  private final ChannelPromise promise;
  private int expectedCount;
  private int doneCount;
  private Throwable lastFailure;
  private boolean doneAllocating;

  public CPAggregator(ChannelPromise promise, Channel c, EventExecutor e) {
    super(c, e);
    assert promise != null && !promise.isDone();
    this.promise = promise;
  }

  public ChannelPromise newPromise() {
    assert !doneAllocating : "Done allocating. No more promises can be allocated.";
    ++expectedCount;
    return this;
  }

  public ChannelPromise doneAllocatingPromises() {
    if (!doneAllocating) {
      doneAllocating = true;
      if (doneCount == expectedCount || expectedCount == 0) {
        return setPromise();
      }
    }
    return this;
  }

  @Override
  public boolean tryFailure(Throwable cause) {
    if (allowFailure()) {
      ++doneCount;
      lastFailure = cause;
      if (allPromisesDone()) {
        return tryPromise();
      }
      // TODO: We break the interface a bit here.
      // Multiple failure events can be processed without issue because this is an aggregation.
      return true;
    }
    return false;
  }

  @Override
  public ChannelPromise setFailure(Throwable cause) {
    if (allowFailure()) {
      ++doneCount;
      lastFailure = cause;
      if (allPromisesDone()) {
        return setPromise();
      }
    }
    return this;
  }

  @Override
  public ChannelPromise setSuccess(Void result) {
    if (awaitingPromises()) {
      ++doneCount;
      if (allPromisesDone()) {
        setPromise();
      }
    }
    return this;
  }

  @Override
  public boolean trySuccess(Void result) {
    if (awaitingPromises()) {
      ++doneCount;
      if (allPromisesDone()) {
        return tryPromise();
      }
      // TODO: We break the interface a bit here.
      // Multiple success events can be processed without issue because this is an aggregation.
      return true;
    }
    return false;
  }

  private boolean allowFailure() {
    return awaitingPromises() || expectedCount == 0;
  }

  private boolean awaitingPromises() {
    return doneCount < expectedCount;
  }

  private boolean allPromisesDone() {
    return doneCount == expectedCount && doneAllocating;
  }

  private ChannelPromise setPromise() {
    if (lastFailure == null) {
      promise.setSuccess();
      return super.setSuccess(null);
    } else {
      promise.setFailure(lastFailure);
      return super.setFailure(lastFailure);
    }
  }

  private boolean tryPromise() {
    if (lastFailure == null) {
      promise.trySuccess();
      return super.trySuccess(null);
    } else {
      promise.tryFailure(lastFailure);
      return super.tryFailure(lastFailure);
    }
  }

}



