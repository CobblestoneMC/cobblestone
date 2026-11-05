/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft;

import java.util.List;
import java.util.Set;

/**
 * Which blocks are someone's build: blocks the world rarely or never generates on its own, so one
 * standing in the way was most likely placed by a player.
 *
 * <p>Read from a block's id, so every platform answers alike. A guess by material, not a record of
 * who placed what: a village's planks and a dungeon's cobblestone count too, which is fine, since a
 * player would not want those broken either. Blocks the world generates in bulk — stone, dirt,
 * logs, sand, sandstone, terracotta in the badlands — are not built, even when a player happens to
 * have placed them.
 */
public final class BuiltBlocks {

  /** Id fragments that mark a crafted building block, wherever they appear in the id. */
  private static final List<String> CRAFTED =
      List.of(
          "planks",
          "glass",
          "brick",
          "concrete",
          "wool",
          "glazed_terracotta",
          "polished_",
          "chiseled_",
          "cut_",
          "smooth_stone",
          "smooth_quartz",
          "smooth_red_sandstone",
          "stripped_",
          "quartz_",
          "copper",
          "_stairs",
          "_slab",
          "_wall",
          "_fence",
          "_bed",
          "_sign",
          "_banner",
          "lantern",
          "shulker_box");

  /** Ids built from, or furnishing a build, that no fragment above catches. */
  private static final Set<String> FURNISHING =
      Set.of(
          "cobblestone",
          "mossy_cobblestone",
          "bookshelf",
          "chiseled_bookshelf",
          "crafting_table",
          "furnace",
          "blast_furnace",
          "smoker",
          "chest",
          "trapped_chest",
          "barrel",
          "anvil",
          "chipped_anvil",
          "damaged_anvil",
          "enchanting_table",
          "lectern",
          "loom",
          "cartography_table",
          "fletching_table",
          "smithing_table",
          "stonecutter",
          "grindstone",
          "composter",
          "jukebox",
          "note_block",
          "beacon",
          "hay_block",
          "iron_block",
          "gold_block",
          "diamond_block",
          "emerald_block",
          "lapis_block",
          "netherite_block",
          "redstone_block",
          "iron_bars",
          "glowstone",
          "sea_lantern",
          "redstone_lamp",
          "target",
          "piston",
          "sticky_piston",
          "observer",
          "dispenser",
          "dropper",
          "hopper");

  /** Ids a fragment above would catch that the world generates in bulk. */
  private static final Set<String> NATURAL = Set.of("smooth_basalt");

  private BuiltBlocks() {}

  /**
   * Returns whether a block of this id is most likely a player's build.
   *
   * @param id the block's id, with or without its namespace ({@code minecraft:oak_planks} or {@code
   *     oak_planks})
   * @return {@code true} if built
   */
  public static boolean isBuilt(String id) {
    String path = id.substring(id.indexOf(':') + 1);
    // Ores, and the raw ore blocks of large veins, are generated, whatever metal they are.
    if (NATURAL.contains(path) || path.endsWith("_ore") || path.startsWith("raw_")) {
      return false;
    }
    if (FURNISHING.contains(path)) {
      return true;
    }
    for (String fragment : CRAFTED) {
      if (path.contains(fragment)) {
        return true;
      }
    }
    return false;
  }
}
