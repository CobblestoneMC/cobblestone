/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper;

import org.bukkit.World;
import org.bukkit.block.data.BlockData;

/**
 * Reads of the terrain around a player by the same rules the search uses, for destinations that
 * look at the world before searching: what is {@linkplain #isPassable passable}, and where a body
 * would {@linkplain #landingY land}. Each reads blocks, so call it on the thread that owns that
 * part of the world.
 */
public final class PaperTerrain {

  private PaperTerrain() {}

  /**
   * Returns whether a body can move through a block — air, plants, open doors and the like, but not
   * water or lava.
   *
   * @param data the block
   * @return {@code true} if it is passable
   */
  public static boolean isPassable(BlockData data) {
    return new PaperBlock(data).isPassable();
  }

  /**
   * Returns where a body at the given position would come to rest by falling straight down: the
   * first y at or below it that has something other than a passable block beneath. If nothing does
   * all the way to the bottom of the world, {@code y} itself.
   *
   * @param world the world
   * @param x the block x-coordinate
   * @param y the block y-coordinate
   * @param z the block z-coordinate
   * @return the y it would land at
   */
  public static int landingY(World world, int x, int y, int z) {
    for (int feet = y; feet > world.getMinHeight(); feet--) {
      if (!isPassable(world.getBlockData(x, feet - 1, z))) {
        return feet;
      }
    }
    return y;
  }
}
