/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.minecraft.api;

import net.whimxiqal.odyssey.api.TraversalKey;

/**
 * The {@link TraversalKey}s used to record Minecraft-specific agent condition in a
 * {@code TraversalState} during a search. Absence of a key means the base (on-foot) state.
 */
public final class MinecraftKeys {

  /** The vehicle the agent is currently riding, if any. */
  public static final TraversalKey<Vehicle> VEHICLE = new TraversalKey<>("vehicle");

  /** Whether the agent's inventory boat has been placed/consumed. */
  public static final TraversalKey<Boolean> BOAT_CONSUMED = new TraversalKey<>("boat_consumed");

  private MinecraftKeys() {
  }

  /** A rideable vehicle carried in the traversal state. */
  public enum Vehicle {
    BOAT,
    HORSE
  }
}
