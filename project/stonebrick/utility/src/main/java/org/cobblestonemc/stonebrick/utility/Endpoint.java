/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.utility;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * What stands at one end of a scenario, said when it is marked.
 *
 * <p>The mistakes a mark can hide are cheap to see here and expensive later. An origin in mid-air
 * is a walker that starts by falling. A destination inside a block cannot be stood on. And a route
 * meant for the surface that ends in a cave measures something else.
 *
 * @param x the block X
 * @param y the block Y
 * @param z the block Z
 * @param feet the block the body occupies
 * @param ground the block beneath it
 * @param biome the biome at the feet
 * @param surface the highest motion-blocking block, leaves aside, in this column
 * @param environment the world's kind
 */
record Endpoint(
    int x,
    int y,
    int z,
    Material feet,
    Material ground,
    String biome,
    int surface,
    World.Environment environment) {

  /**
   * Reads what stands at a block. Must be called on the server thread.
   *
   * @param world the world
   * @param x the block X
   * @param y the block Y
   * @param z the block Z
   * @return the endpoint
   */
  static Endpoint at(World world, int x, int y, int z) {
    Block feet = world.getBlockAt(x, y, z);
    return new Endpoint(
        x,
        y,
        z,
        feet.getType(),
        world.getBlockAt(x, y - 1, z).getType(),
        feet.getBiome().getKey().getKey(),
        world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES),
        world.getEnvironment());
  }

  /**
   * Finds where a body stands in a column: the highest place with ground beneath and room for a
   * body above. Must be called on the server thread.
   *
   * @param world the world
   * @param x the block X
   * @param z the block Z
   * @return the feet Y, or the top of the terrain if nothing in the column can be stood on
   */
  static int standingY(World world, int x, int z) {
    int top = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
    // On water the surface is the top of the water, where a swimmer or a boat is, not the seabed.
    int wet = world.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE);
    if (!world.hasCeiling() && world.getBlockAt(x, wet, z).getType() == Material.WATER) {
      return wet;
    }
    if (world.hasCeiling()) {
      // The nether's highest block is its bedrock roof, and standing on that is not what anyone
      // marking the surface of the nether means: start under the roof instead.
      top = Math.min(top, world.getLogicalHeight() - 6);
    }
    for (int y = top + 1; y > world.getMinHeight(); y--) {
      if (standable(
          world.getBlockAt(x, y - 1, z),
          world.getBlockAt(x, y, z),
          world.getBlockAt(x, y + 1, z))) {
        return y;
      }
    }
    return top + 1;
  }

  /**
   * Finds every place a body can stand in a column, top down: the surface, and every cave floor and
   * structure floor beneath it. Must be called on the server thread.
   *
   * @param world the world
   * @param x the block X
   * @param z the block Z
   * @param minY the lowest feet Y to look at
   * @param maxY the highest feet Y to look at
   * @return the feet Ys, highest first
   */
  static List<Integer> standingLevels(World world, int x, int z, int minY, int maxY) {
    List<Integer> levels = new ArrayList<>();
    int low = Math.max(minY, world.getMinHeight() + 1);
    int high = Math.min(maxY, world.getMaxHeight() - 2);
    for (int y = high; y >= low; y--) {
      if (standable(
          world.getBlockAt(x, y - 1, z),
          world.getBlockAt(x, y, z),
          world.getBlockAt(x, y + 1, z))) {
        levels.add(y);
      }
    }
    return levels;
  }

  private static boolean standable(Block ground, Block feet, Block head) {
    // Lava is passable, and the floor of a lava lake is solid; nobody means to stand there.
    return ground.getType().isSolid()
        && feet.isPassable()
        && head.isPassable()
        && feet.getType() != Material.LAVA
        && head.getType() != Material.LAVA;
  }

  /** Whether this end is underwater. */
  boolean inWater() {
    return feet == Material.WATER;
  }

  /** Whether something solid holds a body up here. */
  boolean grounded() {
    return ground.isSolid() && !feet.isSolid();
  }

  /** How far beneath the open-air surface this end is; negative above it. */
  int depth() {
    return surface + 1 - y;
  }

  /** The tag this end suggests: the dimension, or surface against underground. */
  String suggestedTag() {
    return switch (environment) {
      case NETHER -> "nether";
      case THE_END -> "end";
      // Twelve, not zero: a roof is cover too, and the inside of a house is not a cave.
      default -> depth() > 12 ? "underground" : "surface";
    };
  }

  /**
   * Describes this end in a line, with what is wrong with it if anything is.
   *
   * @param which "origin" or "dest"
   * @return the lines
   */
  List<String> describe(String which) {
    List<String> lines = new ArrayList<>();
    String where =
        switch (environment) {
          case NETHER, THE_END -> "";
          default ->
              inWater()
                  ? ", in water"
                  : depth() > 0
                      ? String.format(Locale.ROOT, ", under %d blocks of cover", depth())
                      : depth() < 0
                          ? String.format(Locale.ROOT, ", %d above the ground", -depth())
                          : "";
        };
    lines.add(
        String.format(
            Locale.ROOT,
            "  %s %d, %d, %d: %s on %s, in %s%s",
            which,
            x,
            y,
            z,
            name(feet),
            name(ground),
            biome,
            where));
    if (feet.isSolid()) {
      lines.add("  ! That is inside a block: nothing can stand there.");
    } else if (!grounded() && !inWater()) {
      lines.add(
          "  ! Nothing solid underneath. Only a flyer can start or end here; a walker will fall.");
    }
    return lines;
  }

  private static String name(Material material) {
    return material.getKey().getKey();
  }
}
