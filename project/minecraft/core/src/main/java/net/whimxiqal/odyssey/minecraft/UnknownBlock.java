/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.minecraft;

import net.whimxiqal.odyssey.minecraft.api.MinecraftBlock;

/**
 * The sentinel block returned for cells whose chunk isn't available under the current load policy:
 * impassable and unbreakable, so modes treat it exactly like a wall they can't get through.
 */
public enum UnknownBlock implements MinecraftBlock {

  INSTANCE;

  @Override
  public String typeKey() {
    return "odyssey:unknown";
  }

  @Override
  public boolean isPassable() {
    return false;
  }

  @Override
  public boolean isSolidTop() {
    return false;
  }

  @Override
  public boolean isWater() {
    return false;
  }
}
