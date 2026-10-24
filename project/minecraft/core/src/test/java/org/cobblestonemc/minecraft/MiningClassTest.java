/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class MiningClassTest {

  @Test
  void everyClassReadsBackFromItsId() {
    for (MiningClass klass : MiningClass.values()) {
      assertEquals(klass, MiningClass.byId(klass.id()));
    }
    assertNull(MiningClass.byId(-1));
    assertNull(MiningClass.byId(MiningClass.MAX_IDS));
  }

  @Test
  void anOreGoesWithTheRockItSitsIn() {
    MiningClass.Tool pickaxe = MiningClass.Tool.PICKAXE;
    assertEquals(MiningClass.STONE, MiningClass.of(pickaxe, false, 3.0, "iron_ore"));
    assertEquals(MiningClass.DEEPSLATE, MiningClass.of(pickaxe, false, 4.5, "deepslate_iron_ore"));
    assertEquals(MiningClass.SANDSTONE, MiningClass.of(pickaxe, false, 3.0, "nether_gold_ore"));
  }

  @Test
  void pickaxeBlocksAreBandedByHardness() {
    MiningClass.Tool pickaxe = MiningClass.Tool.PICKAXE;
    assertEquals(MiningClass.SANDSTONE, MiningClass.of(pickaxe, false, 0.4, "netherrack"));
    assertEquals(MiningClass.STONE, MiningClass.of(pickaxe, false, 1.5, "stone"));
    assertEquals(MiningClass.DEEPSLATE, MiningClass.of(pickaxe, false, 3.0, "deepslate"));
    assertEquals(MiningClass.OBSIDIAN, MiningClass.of(pickaxe, true, 50, "obsidian"));
  }

  @Test
  void otherToolsBucketByTool() {
    assertEquals(MiningClass.DIRT, MiningClass.of(MiningClass.Tool.SHOVEL, false, 0.5, "dirt"));
    assertEquals(MiningClass.WOOD, MiningClass.of(MiningClass.Tool.AXE, false, 2.0, "oak_log"));
    assertEquals(MiningClass.GLASS, MiningClass.of(MiningClass.Tool.HOE, false, 0.2, "oak_leaves"));
    assertEquals(MiningClass.GLASS, MiningClass.of(MiningClass.Tool.NONE, false, 0.3, "glass"));
  }
}
