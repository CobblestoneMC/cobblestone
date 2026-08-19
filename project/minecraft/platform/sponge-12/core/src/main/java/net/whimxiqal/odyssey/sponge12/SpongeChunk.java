/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.sponge12;

import net.whimxiqal.odyssey.minecraft.MinecraftBlock;
import net.whimxiqal.odyssey.minecraft.MinecraftChunk;
import net.whimxiqal.odyssey.minecraft.UnknownBlock;
import org.spongepowered.api.block.BlockState;

/**
 * A {@link MinecraftChunk} backed by a plain 16×height×16 array of {@link BlockState}s copied out
 * of a chunk column on the server thread (Sponge has no {@code ChunkSnapshot}), safe to read from
 * any thread afterwards.
 *
 * <p>We deliberately do not use {@code ServerWorld#createArchetypeVolume}: it also copies block
 * entities, biomes and entities we never read, and its entity leg is broken in Sponge 12 (entity
 * archetypes are offset by half a block and throw {@link IllegalArgumentException} for any entity
 * standing on the volume's minimum face). A block-state stream avoids all of that.
 *
 * <p>Cells never written by the copy stay {@code null} and read back as {@link UnknownBlock} —
 * impassable — rather than silently as air.
 */
final class SpongeChunk implements MinecraftChunk {

  /** Blocks in {@code y}-major order; see {@link #index(int, int, int)}. */
  private final BlockState[] states;

  private final int minY;
  private final int height;

  SpongeChunk(BlockState[] states, int minY, int height) {
    this.states = states;
    this.minY = minY;
    this.height = height;
  }

  /** Index of a local coordinate in {@link #states}; callers must bounds-check first. */
  static int index(int localX, int localY, int localZ) {
    return (localY << 8) | (localZ << 4) | localX;
  }

  @Override
  public MinecraftBlock block(int localX, int y, int localZ) {
    int localY = y - minY;
    if (localX < 0 || localX > 15 || localZ < 0 || localZ > 15 || localY < 0 || localY >= height) {
      return UnknownBlock.INSTANCE;
    }
    BlockState state = states[SpongeChunk.index(localX, localY, localZ)];
    return state == null ? UnknownBlock.INSTANCE : new SpongeBlock(state);
  }
}
