/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.modes;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.cobblestonemc.Cell;
import org.cobblestonemc.minecraft.CobblestonePlayer;
import org.cobblestonemc.minecraft.MinecraftMode;
import org.cobblestonemc.minecraft.TestPlayer;
import org.junit.jupiter.api.Test;

class BlockLookupTest {

  private static final Cell FROM = new Cell(0, 64, 0);

  /**
   * Every mode's declared cells must fit the box one shared fill covers, or the cells outside it
   * would be silently dropped and read back as unknown — which looks like solid rock to a mode, so
   * the failure would be a mysteriously impassable world rather than an error. Widen the shared box
   * if this fails.
   */
  @Test
  void everyModeFitsTheSharedBox() {
    for (MinecraftMode<CobblestonePlayer> mode : allModes()) {
      if (!(mode instanceof AbstractMinecraftMode<CobblestonePlayer> concrete)) {
        continue;
      }
      for (Cell cell : concrete.requiredCells(FROM)) {
        assertTrue(
            Math.abs(cell.x() - FROM.x()) <= BlockLookup.SHARED_XZ_RADIUS
                && Math.abs(cell.z() - FROM.z()) <= BlockLookup.SHARED_XZ_RADIUS
                && cell.y() - FROM.y() >= BlockLookup.SHARED_DY_LOW
                && cell.y() - FROM.y() <= BlockLookup.SHARED_DY_HIGH,
            "%s wants %s, outside the shared box".formatted(mode.getClass().getSimpleName(), cell));
      }
    }
  }

  /** Both a walker's and a flier's mode lists, so no mode escapes the check. */
  private static List<MinecraftMode<CobblestonePlayer>> allModes() {
    List<MinecraftMode<CobblestonePlayer>> modes =
        new java.util.ArrayList<>(MinecraftModes.forPlayer(TestPlayer.walker(), Set.of()));
    modes.addAll(MinecraftModes.forPlayer(TestPlayer.create(true, true, false, true), Set.of()));
    modes.addAll(MinecraftModes.forPlayer(TestPlayer.glider(), Set.of()));
    return modes;
  }
}
