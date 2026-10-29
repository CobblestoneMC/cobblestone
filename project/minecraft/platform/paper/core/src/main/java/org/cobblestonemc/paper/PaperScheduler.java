/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.concurrent.ExecutorService;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.cobblestonemc.Position;
import org.cobblestonemc.SearchThreadPool;
import org.cobblestonemc.minecraft.MinecraftScheduler;
import org.cobblestonemc.minecraft.MinecraftWorld;
import org.cobblestonemc.minecraft.ScheduledTaskHandle;

/**
 * The Paper/Folia {@link MinecraftScheduler}.
 *
 * <p>Search math runs on a {@link SearchThreadPool} (so it never blocks a server thread), while
 * location-aware work is dispatched through Paper's region/global schedulers, which behave
 * correctly on both regular Paper (one main thread) and Folia (per-region threads).
 */
public final class PaperScheduler implements MinecraftScheduler<Entity> {

  private final Plugin plugin;
  private final SearchThreadPool workers;

  /**
   * Creates a scheduler.
   *
   * @param plugin the owning plugin (for region scheduler dispatch)
   * @param workers the threads search work runs on
   */
  public PaperScheduler(Plugin plugin, SearchThreadPool workers) {
    this.plugin = plugin;
    this.workers = workers;
  }

  @Override
  public void runAsync(Runnable task) {
    workers.runAsync(task);
  }

  @Override
  public void runAsyncLater(Runnable task, long delayMillis) {
    workers.runAsyncLater(task, delayMillis);
  }

  @Override
  public ExecutorService asyncExecutor() {
    return workers.asyncExecutor();
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
    workers.shutdown();
  }
}
