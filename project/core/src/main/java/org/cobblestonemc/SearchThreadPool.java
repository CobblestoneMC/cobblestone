/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The threads searches run on: a fixed pool of daemon threads, the production {@link Scheduler}.
 *
 * <p>Nothing here is tied to a game or a server. A platform builds one and hands it to its own
 * scheduler, which adds what is platform-specific (running on a server, region or entity thread)
 * and leaves search work to this pool.
 */
public final class SearchThreadPool implements Scheduler {

  private final ScheduledThreadPoolExecutor threads;

  /**
   * Creates a pool and starts its threads as work arrives.
   *
   * @param name the threads' name, numbered from 1: {@code name-1}, {@code name-2}, …
   * @param size how many threads, at least 1
   */
  public SearchThreadPool(String name, int size) {
    AtomicInteger count = new AtomicInteger();
    this.threads =
        new ScheduledThreadPoolExecutor(
            Math.max(1, size),
            runnable -> {
              Thread thread = new Thread(runnable, name + "-" + count.incrementAndGet());
              thread.setDaemon(true);
              return thread;
            });
  }

  /**
   * Returns how many search threads to run when the configuration leaves it to Cobblestone: half
   * the processors, and at least two.
   *
   * @return the thread count
   */
  public static int defaultSize() {
    return Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
  }

  /**
   * Returns how many threads a configured count asks for: the count itself, or {@link
   * #defaultSize()} for zero or less.
   *
   * @param configured the configured count
   * @return the thread count
   */
  public static int size(int configured) {
    return configured > 0 ? configured : defaultSize();
  }

  @Override
  public void runAsync(Runnable task) {
    threads.execute(task);
  }

  @Override
  public void runAsyncLater(Runnable task, long delayMillis) {
    threads.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
  }

  @Override
  public ExecutorService asyncExecutor() {
    return threads;
  }

  /**
   * Stops the pool at once, interrupting work in progress. Call it last on shutdown, once nothing
   * else waits on search work.
   */
  public void shutdown() {
    threads.shutdownNow();
  }
}
