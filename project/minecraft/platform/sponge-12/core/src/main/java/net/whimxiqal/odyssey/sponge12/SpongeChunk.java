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
import org.spongepowered.api.world.volume.block.BlockVolume;
import org.spongepowered.math.vector.Vector3i;

/**
 * A {@link MinecraftChunk} backed by a Sponge archetype {@link BlockVolume} — an immutable,
 * detached copy of one chunk column taken on the server thread (Sponge has no {@code
 * ChunkSnapshot}, so we copy the region into an archetype volume), safe to read from any thread.
 *
 * <p>Reads are addressed relative to {@link BlockVolume#min() the volume's own minimum}, so this is
 * correct whether the archetype volume indexes by world coordinates or by a zero-based local space.
 * {@code baseMinY} is the world Y that maps to the volume's minimum Y.
 */
final class SpongeChunk implements MinecraftChunk {

  private final BlockVolume volume;
  private final Vector3i min;
  private final Vector3i max;
  private final int baseMinY;

  SpongeChunk(BlockVolume volume, int baseMinY) {
    this.volume = volume;
    this.min = volume.min();
    this.max = volume.max();
    this.baseMinY = baseMinY;
  }

  @Override
  public MinecraftBlock block(int localX, int y, int localZ) {
    int vx = min.x() + localX;
    int vy = min.y() + (y - baseMinY);
    int vz = min.z() + localZ;
    if (vy < min.y() || vy > max.y() || vx > max.x() || vz > max.z()) {
      return UnknownBlock.INSTANCE;
    }
    return new SpongeBlock(volume.block(vx, vy, vz));
  }
}
