/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper;

import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.cobblestonemc.CobblestoneLogger;
import org.cobblestonemc.minecraft.ChunkFetch;
import org.cobblestonemc.minecraft.ChunkLoadPolicy;

/**
 * Obtains chunks on Paper, choosing between the three ways there are to get one.
 *
 * <ol>
 *   <li><b>Snapshot it.</b> A chunk already in memory is snapshotted on its owning thread (via the
 *       region scheduler, so Folia is served too), then read freely from search worker threads.
 *   <li><b>Read it off disk.</b> A chunk that is generated but not loaded is decoded from its saved
 *       NBT by {@link NMSChunkReader}, which never puts it in the chunk system at all.
 *   <li><b>Load it through the server.</b> A chunk with nothing saved that the policy allows
 *       generating, and any chunk the disk read could not produce, goes through Bukkit's async
 *       chunk load.
 * </ol>
 *
 * <p>The second route is an optimization, not a contract — if the read fails, or this server's
 * internals are not the ones Cobblestone was built against, the chunk is loaded through the server
 * instead. Callers see one answer either way; see {@link
 * org.cobblestonemc.minecraft.PlatformApi#fetchChunk}.
 */
final class PaperChunkFetcher {

  private final Plugin plugin;
  private final NMSChunkReader reader;

  PaperChunkFetcher(Plugin plugin, CobblestoneLogger logger) {
    this.plugin = plugin;
    this.reader = new NMSChunkReader(logger);
  }

  /**
   * Fetches one chunk.
   *
   * @param bukkit the world
   * @param chunkX the chunk X
   * @param chunkZ the chunk Z
   * @param policy how far we may go to obtain it
   * @param urgent whether a search is blocked on this chunk, as opposed to reading ahead
   * @return a future of the outcome, which does not fail
   */
  CompletableFuture<ChunkFetch> fetch(
      World bukkit, int chunkX, int chunkZ, ChunkLoadPolicy policy, boolean urgent) {
    if (policy == ChunkLoadPolicy.LOADED_ONLY) {
      if (!bukkit.isChunkLoaded(chunkX, chunkZ)) {
        return CompletableFuture.completedFuture(ChunkFetch.Failed.permanent());
      }
      return snapshotLoaded(bukkit, chunkX, chunkZ);
    }

    // Already in memory: go through the chunk system.
    boolean generate = policy == ChunkLoadPolicy.ALLOW_LOAD_AND_GENERATE;
    if (bukkit.isChunkLoaded(chunkX, chunkZ)) {
      return loadThroughServer(bukkit, chunkX, chunkZ, generate, urgent);
    }
    // Not loaded: read the saved blocks without loading the chunk. Whether there are any is left
    // to the read to discover — asking Bukkit's isChunkGenerated from this thread would block on
    // the main thread, which may itself be waiting on the chunk provider this call is made under.
    if (!reader.available()) {
      // This server's internals aren't the ones we were built against (NMSSupport has said so,
      // once). Load the chunk through the server instead — slower and heavier, but correct.
      return loadThroughServer(bukkit, chunkX, chunkZ, generate, urgent);
    }
    return readOffline(bukkit, chunkX, chunkZ, generate, urgent);
  }

  /**
   * Stops issuing reads and waits, bounded, for the ones underway.
   *
   * @param timeoutMillis how long to wait
   */
  void shutdown(long timeoutMillis) {
    reader.shutdown(timeoutMillis);
  }

  /** Snapshots a chunk that is in memory, on the thread that owns it. */
  private CompletableFuture<ChunkFetch> snapshotLoaded(World bukkit, int chunkX, int chunkZ) {
    CompletableFuture<ChunkFetch> future = new CompletableFuture<>();
    Bukkit.getRegionScheduler()
        .execute(
            plugin,
            bukkit,
            chunkX,
            chunkZ,
            () -> {
              try {
                LoadedPaperChunk chunk =
                    new LoadedPaperChunk(bukkit.getChunkAt(chunkX, chunkZ).getChunkSnapshot());
                future.complete(ChunkFetch.success(chunk));
              } catch (RuntimeException error) {
                future.complete(ChunkFetch.Failed.transientFailure());
              }
            });
    return future;
  }

  /**
   * Reads a chunk's saved blocks, loading it through the server if that cannot be done.
   *
   * <p>Failing to read the chunk off disk is this class's problem, not the caller's: a failed fast
   * path falls back to the slow one, and the caller never learns there were two.
   *
   * <p>Finding nothing saved is an answer about the world, unless {@code generate} allows making
   * the chunk — then it is the cue to have the server generate it.
   */
  private CompletableFuture<ChunkFetch> readOffline(
      World bukkit, int chunkX, int chunkZ, boolean generate, boolean urgent) {
    return reader
        .read(chunkX, chunkZ, bukkit, urgent)
        // Nothing saved, or nothing finished. Moonrise serves writes still queued, so this is the
        // chunk's real state rather than a save that has yet to land. A read cancelled by shutdown
        // also lands here, and must not turn into a server load.
        .thenCompose(
            chunk -> {
              if (chunk != null) {
                return CompletableFuture.completedFuture(ChunkFetch.success(chunk));
              }
              if (generate && !reader.stopped()) {
                return loadThroughServer(bukkit, chunkX, chunkZ, true, urgent);
              }
              return CompletableFuture.completedFuture(ChunkFetch.Failed.permanent());
            })
        .exceptionallyCompose(
            error -> {
              reader.reportFailure(error, chunkX, chunkZ, bukkit);
              return loadThroughServer(bukkit, chunkX, chunkZ, generate, urgent);
            });
  }

  /**
   * Loads a chunk through the server and snapshots it.
   *
   * <p>A {@code null} chunk means it does not exist and {@code generate} forbade creating it — an
   * answer about the world. A failed load says nothing about the chunk, so it is worth asking
   * again.
   */
  private CompletableFuture<ChunkFetch> loadThroughServer(
      World bukkit, int chunkX, int chunkZ, boolean generate, boolean urgent) {
    return bukkit
        .getChunkAtAsync(chunkX, chunkZ, generate, urgent)
        .thenApply(
            chunk ->
                chunk == null
                    ? ChunkFetch.Failed.permanent()
                    : ChunkFetch.success(new LoadedPaperChunk(chunk.getChunkSnapshot())))
        .exceptionally(_ -> ChunkFetch.Failed.transientFailure());
  }
}
