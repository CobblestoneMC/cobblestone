/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.minecraft.modes;

import java.util.Map;
import net.whimxiqal.odyssey.Cell;
import net.whimxiqal.odyssey.minecraft.MinecraftBlock;
import net.whimxiqal.odyssey.minecraft.UnknownBlock;

/**
 * A read-only snapshot of the blocks a mode fetched for one expansion. Cells not present (i.e. not
 * requested, or unavailable) resolve to {@link UnknownBlock#INSTANCE}.
 *
 * @param blocks the fetched blocks keyed by cell
 */
record BlockView(Map<Cell, MinecraftBlock> blocks) {

  MinecraftBlock at(Cell cell) {
    return blocks.getOrDefault(cell, UnknownBlock.INSTANCE);
  }

  MinecraftBlock at(Cell cell, int dx, int dy, int dz) {
    return at(cell.plus(dx, dy, dz));
  }
}
