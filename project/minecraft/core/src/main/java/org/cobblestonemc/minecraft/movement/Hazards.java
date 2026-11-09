/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.movement;

import org.cobblestonemc.minecraft.MinecraftKeys;
import org.jetbrains.annotations.Nullable;

/**
 * Which of the hazards {@link Surroundings} looks for a player is priced for, worked out once per
 * search from the player and the weights: a hazard whose weight is 0, or that cannot touch this
 * player, is not looked for, and costs no reads.
 *
 * @param dangerRadius how far away dangerous terrain is noticed, or 0 if it is not priced
 * @param coast whether the player boats, so the shore slows them
 * @param ledges whether the drop off a ledge counts as dangerous terrain: not to a player who flies
 */
record Hazards(int dangerRadius, boolean coast, boolean ledges) {

  static final Hazards NONE = new Hazards(0, false, false);

  boolean isNone() {
    return dangerRadius == 0 && !coast;
  }

  /**
   * Returns the hazards an expansion in a vehicle can meet: a boat only the shore, a horse only
   * dangerous terrain, and on foot everything but the shore.
   */
  Hazards meetable(MinecraftKeys.@Nullable Vehicle vehicle) {
    if (vehicle == MinecraftKeys.Vehicle.BOAT) {
      return coast ? new Hazards(0, true, false) : NONE;
    }
    if (vehicle == MinecraftKeys.Vehicle.HORSE) {
      return dangerRadius > 0 ? new Hazards(dangerRadius, false, ledges) : NONE;
    }
    return coast ? new Hazards(dangerRadius, false, ledges) : this;
  }
}
