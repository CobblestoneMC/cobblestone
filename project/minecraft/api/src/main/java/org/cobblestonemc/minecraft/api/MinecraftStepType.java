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
  WALK,
  JUMP,
  SWIM,
  FLY,
  MINE,
  FALL,
  CLIMB,
  BOAT,
  HORSE,

  OPEN_DOOR(true),
  PLACE_BOAT(true),
  MOUNT_HORSE(true),
  TELEPORT(true),

  // reserved (unimplemented in v1)
  ELYTRA,
  RIDE_MINECART;

  final boolean action;

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
