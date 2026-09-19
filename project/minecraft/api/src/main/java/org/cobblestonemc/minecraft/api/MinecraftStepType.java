/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.api;

/**
 * The {@code StepType} enum for Minecraft — both movement types (produced by modes) and
 * discrete-action types (produced by transitions, or by a vehicle mode's first movement).
 */
public enum MinecraftStepType {

  // movement
  /** Walk along the ground. */
  WALK,
  /** Jump up a block. */
  JUMP,
  /** Swim through water. */
  SWIM,
  /** Fly. */
  FLY,
  /** Break a block in the way. */
  MINE,
  /** Drop down. */
  FALL,
  /** Climb a ladder, vine or similar. */
  CLIMB,
  /** Travel by boat. */
  BOAT,
  /** Ride a horse. */
  HORSE,

  /** Open a door. */
  OPEN_DOOR(true),
  /** Place a boat to board. */
  PLACE_BOAT(true),
  /** Mount a horse. */
  MOUNT_HORSE(true),
  /** Teleport, e.g. through a portal or by running a command. */
  TELEPORT(true),

  // reserved (unimplemented in v1)
  /** Glide with an elytra (reserved; not produced yet). */
  ELYTRA,
  /** Ride a minecart (reserved; not produced yet). */
  RIDE_MINECART;

  final boolean action;

  /**
   * Returns whether this is a discrete action (door, boat, mount, teleport) rather than movement.
   *
   * @return {@code true} for an action type
   */
  public boolean isAction() {
    return action;
  }

  MinecraftStepType() {
    this.action = false;
  }

  MinecraftStepType(boolean action) {
    this.action = action;
  }
}
