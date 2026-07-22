/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

/** An {@link java.util.concurrent.ExecutorService} that runs every task inline, for deterministic tests. */
final class DirectExecutorService extends AbstractExecutorService {

  private volatile boolean shutdown;

  @Override
  public void execute(Runnable command) {
    command.run();
  }

  @Override
  public void shutdown() {
    shutdown = true;
  }

  @Override
  public List<Runnable> shutdownNow() {
    shutdown = true;
    return List.of();
  }

  @Override
  public boolean isShutdown() {
    return shutdown;
  }

  @Override
  public boolean isTerminated() {
    return shutdown;
  }

  @Override
  public boolean awaitTermination(long timeout, TimeUnit unit) {
    return true;
  }
}
