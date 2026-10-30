/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BuiltBlocksTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "minecraft:oak_planks",
        "cobblestone",
        "stone_bricks",
        "glass_pane",
        "white_concrete",
        "spruce_stairs",
        "stone_slab",
        "cobblestone_wall",
        "oak_fence",
        "chest",
        "crafting_table",
        "stripped_oak_log",
        "waxed_cut_copper",
        "red_bed"
      })
  void aPlayersBuildIsBuilt(String id) {
    assertTrue(BuiltBlocks.isBuilt(id), id);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "minecraft:stone",
        "dirt",
        "grass_block",
        "oak_log",
        "oak_leaves",
        "sand",
        "sandstone",
        "terracotta",
        "red_terracotta",
        "deepslate",
        "copper_ore",
        "deepslate_copper_ore",
        "nether_quartz_ore",
        "raw_iron_block",
        "smooth_basalt",
        "water"
      })
  void whatTheWorldGeneratesIsNot(String id) {
    assertFalse(BuiltBlocks.isBuilt(id), id);
  }
}
