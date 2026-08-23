/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc;

import java.util.concurrent.ExecutorService;

/** A {@link Scheduler} that runs everything inline, so searches complete synchronously in tests. */
final class InlineScheduler implements Scheduler {

  private final ExecutorService executor = new DirectExecutorService();

  @Override
  public void runAsync(Runnable task) {
    executor.execute(task);
  }

  @Override
  public void runAsyncLater(Runnable task, long delayMillis) {
    executor.execute(task);
  }

  @Override
  public ExecutorService asyncExecutor() {
    return executor;
  }
}
