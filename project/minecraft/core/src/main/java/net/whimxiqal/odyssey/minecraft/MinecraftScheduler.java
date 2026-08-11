/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.minecraft;

import net.whimxiqal.odyssey.Position;
import net.whimxiqal.odyssey.Scheduler;

/**
 * The Minecraft {@link Scheduler}, adding location-aware scheduling so world state can be read on
 * the thread that owns a location (Paper: main thread; Folia: the owning region; Sponge: server
 * thread).
 */
public interface MinecraftScheduler<E> extends Scheduler {

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

  /**
   * Runs a task repeatedly on the thread that owns the given location, until cancelled. Used to
   * tick a trip's navigator (rendering the trail). The period is in server ticks (20 ticks per
   * second).
   *
   * @param position the location whose owning thread runs the task
   * @param task the task to run each period
   * @param periodTicks the number of ticks between runs (clamped to at least 1)
   * @return a handle to cancel the repeating task
   */
  ScheduledTaskHandle runAtPositionRepeating(
      Position<? extends MinecraftWorld> position, Runnable task, long periodTicks);

  void runAtEntity(E entity, Runnable task);

  ScheduledTaskHandle runAtEntityRepeating(E entity, Runnable task, long periodTicks);
}
