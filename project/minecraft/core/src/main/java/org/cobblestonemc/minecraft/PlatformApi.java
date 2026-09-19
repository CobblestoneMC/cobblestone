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
   * policy. The future may complete on any thread.
   *
   * <p><b>Whatever this completes with is the answer.</b> {@link MinecraftChunk.Unknown} means the
   * chunk's contents cannot be known — the policy forbids materializing it, the world is gone,
   * nothing is saved there — and the caller caches it as such. It must never mean "ask me again",
   * and in particular {@code urgent} must not change the answer: a platform that would decline
   * speculative work and do it for a blocked caller would have the cache remember a refusal as a
   * fact about the world.
   *
   * <p>A platform with more than one way to obtain a chunk resolves that here, not by answering
   * differently. If a fast path fails — an offline read of a chunk that will not decode, internals
   * that moved between server versions — it composes the fallback into the future it returns, so
   * one future carries the whole question. Failing the future is allowed and means every route was
   * exhausted; the caller treats that as unknown too.
   *
   * @param chunkX the chunk X coordinate
   * @param chunkZ the chunk Z coordinate
   * @param world the world
   * @param policy the load policy
   * @param urgent whether a caller is blocked on this chunk rather than reading ahead; a hint for
   *     ordering and priority only, never for what the answer is
   * @return a future of the snapshot, or of {@link MinecraftChunk.Unknown}
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
