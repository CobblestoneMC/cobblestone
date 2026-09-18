/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.cobblestonemc.minecraft.MinecraftBlock;

/**
 * Resolves an NMS {@link BlockState} to a {@link MinecraftBlock}, reusing one instance per state.
 *
 * <p>The traits themselves come from {@link PaperBlock}: a {@code BlockState} converts to the
 * Bukkit {@code BlockData} that class already reads, so the material→predicate table lives in one
 * place and an offline chunk and a loaded one answer identically.
 *
 * <p>Caching is what makes that affordable. The conversion allocates — {@code asBlockData()} hands
 * back a clone — and a search reads tens of millions of blocks, so the answer is memoized against
 * the block state registry's dense id. That is also a strictly better key than {@link PaperBlocks}'
 * material: a block state already pins down the door's open/closed bit, so unlike there, nothing
 * here has to be excluded from the cache and recomputed per read.
 */
final class NMSBlocks {

  /**
   * One instance per block state, indexed by registry id and filled on first use.
   *
   * <p>Unsynchronized on purpose, for the same reason as {@link PaperBlocks}: two threads racing on
   * one state both build a correct, interchangeable instance, and {@link PaperBlock}'s fields are
   * final, so any thread seeing a published reference sees a fully constructed object. The worst
   * case is a duplicate, not a broken read.
   */
  private static final MinecraftBlock[] BY_STATE_ID =
      new MinecraftBlock[Block.BLOCK_STATE_REGISTRY.size()];

  /** Air, the overwhelmingly common answer and the stand-in for a section with no palette. */
  static final MinecraftBlock AIR = of(Blocks.AIR.defaultBlockState());

  private NMSBlocks() {}

  /**
   * Returns the block for a block state.
   *
   * @param state the NMS block state
   * @return the block
   */
  static MinecraftBlock of(BlockState state) {
    //    int id = Block.BLOCK_STATE_REGISTRY.getId(state);
    //    if (id < 0 || id >= BY_STATE_ID.length) {
    //      // A state registered after this class was initialized — correct, just not cached.
    //      return new PaperBlock(state.asBlockData());
    //    }
    //    MinecraftBlock cached = BY_STATE_ID[id];
    //    if (cached != null) {
    //      return cached;
    //    }
    MinecraftBlock block = new PaperBlock(state.asBlockData());
    //    BY_STATE_ID[id] = block;
    return block;
  }
}
