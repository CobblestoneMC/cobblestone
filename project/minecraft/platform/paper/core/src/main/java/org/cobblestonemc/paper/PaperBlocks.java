/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.cobblestonemc.minecraft.MinecraftBlock;

/**
 * Resolves a block out of a {@link PaperChunk}, reusing one {@link PaperBlock} per material.
 *
 * <p>Every trait {@link PaperBlock} exposes is derived from the material alone, with one exception:
 * {@code isOpen()} reads the live {@code Openable} state of a door, gate or trapdoor. So for every
 * other material the answer is identical for all blocks of that material, and one shared immutable
 * instance serves them all.
 *
 * <p>That matters because of the volume. Reading a block's {@link PaperChunk#blockData} allocates a
 * fresh copy plus a wrapper, and a search reads tens of millions of blocks. {@link
 * PaperChunk#blockType} returns an enum constant and allocates nothing, so the common path costs an
 * array index — and it costs the same whether the chunk was snapshotted from memory or read off
 * disk.
 *
 * <p>Because of that sharing, a cached instance's block data is the material's <em>default</em>
 * state. Nothing here reads state off it (only openables do, and they are never cached), but a
 * caller that needs a block's real state — an integration break checker — must read it from the
 * chunk rather than from one of these; see {@link LazyBlockData}.
 */
final class PaperBlocks {

  /**
   * One instance per material, indexed by ordinal and filled on first use.
   *
   * <p>Unsynchronized on purpose: two threads racing on the same material both build a correct,
   * interchangeable instance, and {@link PaperBlock}'s fields are final, so any thread that sees a
   * published reference sees a fully constructed object. The worst case is a duplicate, not a
   * broken read.
   */
  private static final PaperBlock[] BY_MATERIAL = new PaperBlock[Material.values().length];

  private PaperBlocks() {}

  /**
   * Returns the block at the given local coordinates of a chunk.
   *
   * @param chunk the chunk
   * @param localX 0–15
   * @param y the world Y
   * @param localZ 0–15
   * @return the block
   */
  static MinecraftBlock of(PaperChunk chunk, int localX, int y, int localZ) {
    Material material = chunk.blockType(localX, y, localZ);
    if (isStateful(material)) {
      return new PaperBlock(chunk.blockData(localX, y, localZ));
    }
    PaperBlock cached = BY_MATERIAL[material.ordinal()];
    if (cached != null) {
      return cached;
    }
    PaperBlock block = new PaperBlock(material.createBlockData());
    BY_MATERIAL[material.ordinal()] = block;
    return block;
  }

  /** Whether this material's traits depend on its live block state (only openables do). */
  private static boolean isStateful(Material material) {
    return Tag.DOORS.isTagged(material)
        || Tag.TRAPDOORS.isTagged(material)
        || Tag.FENCE_GATES.isTagged(material);
  }
}
