/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper;

import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.cobblestonemc.minecraft.regions.MinecraftTerrain;

/**
 * Reads of the terrain around a player by the same rules the search uses, for destinations that
 * look at the world before searching: what is {@linkplain #isPassable passable}, what is
 * {@linkplain #isOpenToSky open to the sky}, and where a body would {@linkplain #landingY land}.
 * Each reads blocks, so call it on the thread that owns that part of the world.
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
   * Returns whether one block replacing another leaves the region layer's terrain as it was; see
   * {@link MinecraftTerrain#classifiesAlike}.
   *
   * @param before the block that was there
   * @param after the block that is there now
   * @return {@code true} if the change does not matter to the regions
   */
  public static boolean classifiesAlike(BlockData before, BlockData after) {
    return MinecraftTerrain.classifiesAlike(new PaperBlock(before), new PaperBlock(after));
  }

  /**
   * Returns whether the block at the given position is open to the sky: nothing but passable blocks
   * above it, all the way to the top of the world, as the {@code surface} destinations mean it.
   * Water and leaves count as a roof.
   *
   * @param world the world
   * @param x the block x-coordinate
   * @param y the block y-coordinate
   * @param z the block z-coordinate
   * @return {@code true} if every block above it is passable
   */
  public static boolean isOpenToSky(World world, int x, int y, int z) {
    for (int above = world.getMaxHeight() - 1; above > y; above--) {
      if (!isPassable(world.getBlockData(x, above, z))) {
        return false;
      }
    }
    return true;
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
