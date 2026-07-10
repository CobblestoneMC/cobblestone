/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.minecraft.api;

import net.whimxiqal.odyssey.api.Position;
import net.whimxiqal.odyssey.api.Scheduler;

/**
 * The Minecraft {@link Scheduler}, adding location-aware scheduling so world state can be read on the
 * thread that owns a location (Paper: main thread; Folia: the owning region; Sponge: server thread).
 */
public interface MinecraftScheduler extends Scheduler {

  /**
   * Runs a task on the thread that owns the given location.
   *
   * @param position the location
   * @param task the task
   */
  void runAtPosition(Position<? extends MinecraftWorld> position, Runnable task);

  /**
   * Runs a task on the global/main thread (Folia: the global region thread).
   *
   * @param task the task
   */
  void runGlobal(Runnable task);
}
