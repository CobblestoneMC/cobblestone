/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.minecraft.api;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The seam each platform (Paper/Folia, Sponge) fills to connect Odyssey's world model to the live
 * server. The chunk provider and world implementations are built on top of this.
 */
public interface PlatformApi {

  /**
   * Returns the platform scheduler.
   *
   * @return the scheduler
   */
  MinecraftScheduler scheduler();

  /**
   * Fetches an immutable snapshot of the chunk at the given chunk coordinates, honoring the load
   * policy. The future may complete on any thread; it yields empty when the policy forbids
   * materializing that chunk.
   *
   * @param chunkX the chunk X coordinate
   * @param chunkZ the chunk Z coordinate
   * @param world the world
   * @param policy the load policy
   * @return a future of the snapshot, or empty
   */
  CompletableFuture<Optional<MinecraftChunk>> fetchChunk(
      int chunkX, int chunkZ, MinecraftWorld world, ChunkLoadPolicy policy);
}
