/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey;

import java.util.concurrent.ExecutorService;

/**
 * Platform abstraction for running work off the server thread(s).
 *
 * <p>The core search runs entirely on worker threads via this seam; platform implementations
 * extend it with location-aware scheduling for fetching world state. "Async" throughout Odyssey
 * means "on a worker thread".
 */
public interface Scheduler {

  /**
   * Runs a task on a worker thread.
   *
   * @param task the task
   */
  void runAsync(Runnable task);

  /**
   * Runs a task on a worker thread after a delay.
   *
   * @param task the task
   * @param delayMillis the delay in milliseconds
   */
  void runAsyncLater(Runnable task, long delayMillis);

  /**
   * Returns the executor backing {@link #runAsync}, for {@link java.util.concurrent.CompletableFuture}
   * composition.
   *
   * @return the async executor
   */
  ExecutorService asyncExecutor();
}
