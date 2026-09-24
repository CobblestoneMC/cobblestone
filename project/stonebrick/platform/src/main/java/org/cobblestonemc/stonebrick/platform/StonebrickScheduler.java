/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.platform;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.cobblestonemc.Position;
import org.cobblestonemc.minecraft.MinecraftScheduler;
import org.cobblestonemc.minecraft.MinecraftWorld;
import org.cobblestonemc.minecraft.ScheduledTaskHandle;

/**
 * A scheduler over a plain thread pool.
 *
 * <p>There is no server, so the location- and entity-aware variants have nothing to be aware of:
 * every task runs on the pool. That is faithful rather than lazy — the only reason a live platform
 * pins work to a region or an entity is that Minecraft's own state lives there, and a capture has
 * no mutable state to protect.
 *
 * <p>This is the realistic-concurrency scheduler. The deterministic one that makes A/B comparison
 * possible is a separate implementation; a benchmark should be run under both, the first to compare
 * algorithms and the second to check they still behave when threads are real.
 */
public final class StonebrickScheduler implements MinecraftScheduler<Object>, AutoCloseable {

  private final ScheduledExecutorService executor;

  /**
   * Creates a scheduler with the given worker count.
   *
   * @param threads how many worker threads to run
   */
  public StonebrickScheduler(int threads) {
    this.executor =
        Executors.newScheduledThreadPool(
            threads,
            runnable -> {
              Thread thread = new Thread(runnable, "stonebrick-worker");
              thread.setDaemon(true);
              return thread;
            });
  }

  @Override
  public void runAsync(Runnable task) {
    executor.execute(task);
  }

  @Override
  public void runAsyncLater(Runnable task, long delayMillis) {
    executor.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
  }

  @Override
  public java.util.concurrent.ExecutorService asyncExecutor() {
    return executor;
  }

  @Override
  public void runAtPosition(Position<? extends MinecraftWorld> position, Runnable task) {
    executor.execute(task);
  }

  @Override
  public void runGlobal(Runnable task) {
    executor.execute(task);
  }

  @Override
  public ScheduledTaskHandle runAtPositionRepeating(
      Position<? extends MinecraftWorld> position, Runnable task, long periodTicks) {
    return repeating(task, periodTicks);
  }

  @Override
  public void runAtEntity(Object entity, Runnable task) {
    executor.execute(task);
  }

  @Override
  public ScheduledTaskHandle runAtEntityRepeating(Object entity, Runnable task, long periodTicks) {
    return repeating(task, periodTicks);
  }

  private ScheduledTaskHandle repeating(Runnable task, long periodTicks) {
    long periodMillis = Math.max(1, periodTicks) * 50L;
    ScheduledFuture<?> future =
        executor.scheduleAtFixedRate(task, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
    return () -> future.cancel(false);
  }

  @Override
  public void close() {
    executor.shutdownNow();
  }
}
