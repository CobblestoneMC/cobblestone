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
    for (MiningClass clazz : MiningClass.values()) {
      assertEquals(clazz, MiningClass.byId(clazz.id()));
    }
    assertNull(MiningClass.byId(-1));
    assertNull(MiningClass.byId(MiningClass.MAX_IDS));
  }

  @Test
  void anOreGoesWithTheRockItSitsIn() {
    MiningClass.Tool pickaxe = MiningClass.Tool.PICKAXE;
    assertEquals(MiningClass.ROCK, MiningClass.of(pickaxe, false, 3.0, "iron_ore"));
    assertEquals(MiningClass.HARD_ROCK, MiningClass.of(pickaxe, false, 4.5, "deepslate_iron_ore"));
    assertEquals(MiningClass.SOFT_ROCK, MiningClass.of(pickaxe, false, 3.0, "nether_gold_ore"));
  }

  @Test
  void rockIsBandedByHardness() {
    MiningClass.Tool pickaxe = MiningClass.Tool.PICKAXE;
    assertEquals(MiningClass.SOFT_ROCK, MiningClass.of(pickaxe, false, 0.4, "netherrack"));
    assertEquals(MiningClass.ROCK, MiningClass.of(pickaxe, false, 1.5, "stone"));
    assertEquals(MiningClass.HARD_ROCK, MiningClass.of(pickaxe, false, 3.0, "deepslate"));
    assertEquals(MiningClass.VERY_HARD_ROCK, MiningClass.of(pickaxe, true, 50, "obsidian"));
  }

  @Test
  void otherToolsBucketByTool() {
    assertEquals(MiningClass.LOOSE, MiningClass.of(MiningClass.Tool.SHOVEL, false, 0.5, "dirt"));
    assertEquals(MiningClass.WOODEN, MiningClass.of(MiningClass.Tool.AXE, false, 2.0, "oak_log"));
  }

  @Test
  void blocksNoToolHelpsAreSoftOrBandedAsRock() {
    MiningClass.Tool none = MiningClass.Tool.NONE;
    assertEquals(MiningClass.SOFT, MiningClass.of(none, false, 0.3, "glass"));
    assertEquals(MiningClass.SOFT, MiningClass.of(none, false, 0.2, "oak_leaves"));
    assertEquals(MiningClass.ROCK, MiningClass.of(none, false, 1.0, "nether_wart_block"));
    assertEquals(MiningClass.HARD_ROCK, MiningClass.of(none, false, 3.0, "sculk_catalyst"));
    assertEquals(
        MiningClass.VERY_HARD_ROCK, MiningClass.of(none, false, 55, "reinforced_deepslate"));
  }
}
