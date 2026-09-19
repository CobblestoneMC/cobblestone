/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft;

import java.util.concurrent.CompletableFuture;

/**
 * The seam each platform (Paper/Folia, Sponge) fills to connect Cobblestone's world model to the
 * live server. The chunk provider and world implementations are built on top of this.
 */
public interface PlatformApi<E> {

  /**
   * Returns the platform scheduler.
   *
   * @return the scheduler
   */
  MinecraftScheduler<E> scheduler();

  /**
   * Fetches an immutable snapshot of the chunk at the given chunk coordinates, honoring the load
   * policy. The future may complete on any thread; it yields empty when the policy forbids
   * materializing that chunk.
   *
   * @param chunkX the chunk X coordinate
   * @param chunkZ the chunk Z coordinate
   * @param world the world
   * @param policy the load policy
   * @param urgent the urgency of the request
   * @return a future of the snapshot, or null
   */
  CompletableFuture<MinecraftChunk> fetchChunk(
      int chunkX, int chunkZ, MinecraftWorld world, ChunkLoadPolicy policy, boolean urgent);

  /**
   * Stops issuing new work, calls off whatever has not started, and waits — bounded — for what has.
   *
   * <p>Called on plugin disable, before the worker pool stops. Cancelling first is what keeps the
   * wait short; waiting at all is what stops Cobblestone from walking away from reads the server is
   * still doing on its behalf, against files it is about to close. Chunk caches belong to a solve
   * and go away with it, so the platform is the only thing left holding work.
   *
   * <p>Must return even if the work never finishes: a read that will never complete must not hold
   * the server open. Platforms that queue nothing of their own have nothing to do here.
   */
  default void shutdown() {}
}
