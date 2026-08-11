/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.whimxiqal.odyssey.Position;
import net.whimxiqal.odyssey.minecraft.MinecraftScheduler;
import net.whimxiqal.odyssey.minecraft.MinecraftWorld;
import net.whimxiqal.odyssey.minecraft.ScheduledTaskHandle;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/**
 * The Paper/Folia {@link MinecraftScheduler}.
 *
 * <p>Search math runs on a dedicated daemon worker pool (so it never blocks a server thread), while
 * location-aware work is dispatched through Paper's region/global schedulers, which behave
 * correctly on both regular Paper (one main thread) and Folia (per-region threads).
 */
public final class PaperScheduler implements MinecraftScheduler<Entity> {

  private final Plugin plugin;
  private final ScheduledExecutorService workers;

  /**
   * Creates a scheduler.
   *
   * @param plugin the owning plugin (for region scheduler dispatch)
   * @param workerThreads the number of search worker threads
   */
  public PaperScheduler(Plugin plugin, int workerThreads) {
    this.plugin = plugin;
    AtomicInteger counter = new AtomicInteger();
    this.workers =
        Executors.newScheduledThreadPool(
            workerThreads,
            runnable -> {
              Thread thread = new Thread(runnable, "odyssey-search-" + counter.incrementAndGet());
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
    NamespacedKey key = NamespacedKey.fromString(position.domain().key());
    World world = key == null ? null : Bukkit.getWorld(key);
    if (world == null) {
      return;
    }
    Bukkit.getRegionScheduler()
        .execute(plugin, world, position.cell().x() >> 4, position.cell().z() >> 4, task);
  }

  @Override
  public void runGlobal(Runnable task) {
    Bukkit.getGlobalRegionScheduler().execute(plugin, task);
  }

  @Override
  public ScheduledTaskHandle runAtPositionRepeating(
      Position<? extends MinecraftWorld> position, Runnable task, long periodTicks) {
    NamespacedKey key = NamespacedKey.fromString(position.domain().key());
    World world = key == null ? null : Bukkit.getWorld(key);
    if (world == null) {
      return () -> {};
    }
    ScheduledTask scheduled =
        Bukkit.getRegionScheduler()
            .runAtFixedRate(
                plugin,
                world,
                position.cell().x() >> 4,
                position.cell().z() >> 4,
                ignored -> task.run(),
                1L,
                Math.max(1L, periodTicks));
    return scheduled::cancel;
  }

  @Override
  public void runAtEntity(Entity entity, Runnable task) {
    entity.getScheduler().run(plugin, _ -> task.run(), null);
  }

  @Override
  public ScheduledTaskHandle runAtEntityRepeating(Entity entity, Runnable task, long periodTicks) {
    ScheduledTask scheduled =
        entity
            .getScheduler()
            .runAtFixedRate(plugin, _ -> task.run(), null, 1L, Math.max(1L, periodTicks));
    return () -> {
      if (scheduled != null) {
        scheduled.cancel();
      }
    };
  }

  /** Stops the worker pool; call on plugin disable. */
  public void shutdown() {
    workers.shutdownNow();
  }
}
