/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge12;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.cobblestonemc.Position;
import org.cobblestonemc.minecraft.MinecraftScheduler;
import org.cobblestonemc.minecraft.MinecraftWorld;
import org.cobblestonemc.minecraft.ScheduledTaskHandle;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.entity.Entity;
import org.spongepowered.api.scheduler.ScheduledTask;
import org.spongepowered.api.scheduler.Task;
import org.spongepowered.api.util.Ticks;
import org.spongepowered.plugin.PluginContainer;

/**
 * The Sponge {@link MinecraftScheduler}.
 *
 * <p>Search math runs on a dedicated daemon worker pool (so it never blocks the server thread).
 * Location- and entity-aware work is dispatched through Sponge's synchronous server scheduler,
 * which ticks on the single main server thread (Sponge has no Folia-style per-region threading).
 */
public final class SpongeScheduler implements MinecraftScheduler<Entity> {

  private final PluginContainer plugin;
  private final ScheduledExecutorService workers;

  /**
   * Creates a scheduler.
   *
   * @param plugin the owning plugin container (for task dispatch)
   * @param workerThreads the number of search worker threads
   */
  public SpongeScheduler(PluginContainer plugin, int workerThreads) {
    this.plugin = plugin;
    AtomicInteger counter = new AtomicInteger();
    this.workers =
        Executors.newScheduledThreadPool(
            workerThreads,
            runnable -> {
              Thread thread =
                  new Thread(runnable, "cobblestone-search-" + counter.incrementAndGet());
              thread.setDaemon(true);
              return thread;
            });
  }

  @Override
  public void runAsync(Runnable task) {
    workers.execute(task);
  }

  @Override
  public void runAsyncLater(Runnable task, long delayMillis) {
    workers.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
  }

  @Override
  public ExecutorService asyncExecutor() {
    return workers;
  }

  @Override
  public void runAtPosition(Position<? extends MinecraftWorld> position, Runnable task) {
    runSync(task);
  }

  @Override
  public void runGlobal(Runnable task) {
    runSync(task);
  }

  @Override
  public ScheduledTaskHandle runAtPositionRepeating(
      Position<? extends MinecraftWorld> position, Runnable task, long periodTicks) {
    return syncRepeating(task, periodTicks);
  }

  @Override
  public void runAtEntity(Entity entity, Runnable task) {
    runSync(task);
  }

  @Override
  public ScheduledTaskHandle runAtEntityRepeating(Entity entity, Runnable task, long periodTicks) {
    return syncRepeating(task, periodTicks);
  }

  /** Submits a one-shot task to the main server thread. */
  private void runSync(Runnable task) {
    Sponge.server().scheduler().submit(Task.builder().plugin(plugin).execute(task).build());
  }

  /** Submits a fixed-rate task to the main server thread, returning a cancel handle. */
  private ScheduledTaskHandle syncRepeating(Runnable task, long periodTicks) {
    ScheduledTask scheduled =
        Sponge.server()
            .scheduler()
            .submit(
                Task.builder()
                    .plugin(plugin)
                    .execute(task)
                    .delay(Ticks.of(1L))
                    .interval(Ticks.of(Math.max(1L, periodTicks)))
                    .build());
    return scheduled::cancel;
  }

  /** Stops the worker pool; call on plugin disable. */
  public void shutdown() {
    workers.shutdownNow();
  }
}
