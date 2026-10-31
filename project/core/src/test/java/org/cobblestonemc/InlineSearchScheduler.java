/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc;

import java.util.concurrent.Executor;

/**
 * A {@link SearchScheduler} that runs everything inline, delays included, so searches complete
 * synchronously in tests.
 */
final class InlineSearchScheduler implements SearchScheduler, TimeSource {

  @Override
  public Executor executor() {
    return Runnable::run;
  }

  @Override
  public TimeSource time() {
    return this;
  }

  @Override
  public long millis() {
    return System.currentTimeMillis();
  }

  @Override
  public void schedule(Runnable task, long delayMillis) {
    task.run();
  }
}
