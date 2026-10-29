/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge;

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
 * <p>Location- and entity-aware work is dispatched through Sponge's synchronous server scheduler,
 * which ticks on the single main server thread (Sponge has no Folia-style per-region threading).
 */
public final class SpongeScheduler implements MinecraftScheduler<Entity> {

  private final PluginContainer plugin;

  /**
   * Creates a scheduler.
   *
   * @param plugin the owning plugin container (for task dispatch)
   */
  public SpongeScheduler(PluginContainer plugin) {
    this.plugin = plugin;
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
}
