/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge;

import java.util.concurrent.CompletableFuture;
import org.cobblestonemc.minecraft.MinecraftChunk;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.api.world.server.ServerWorld;

/**
 * Reads a chunk's saved blocks without loading the chunk — the one thing the Sponge platform cannot
 * do through SpongeAPI, and the only reason Cobblestone ships a separate jar per Minecraft version.
 *
 * <p>SpongeAPI has no way to ask "what blocks are saved at this chunk". {@code
 * ServerWorld#offlineChunks()} streams the whole world and hands back raw data containers, with no
 * lookup by coordinate; everything else requires a chunk loading ticket, which drives the chunk to
 * full status and costs what {@link SpongeChunkLoader} exists to manage. Reading the region files
 * directly means reading server internals, and those move between Minecraft versions — hence this
 * interface, implemented once per supported version and injected from that version's module.
 *
 * <p><b>The contract mirrors Paper's.</b> A future that <em>fails</em> means only that this fast
 * path did not work — a region file that will not open, an internal that moved — and the caller
 * answers it by falling back to the ticket machinery, so nothing above ever learns there were two
 * ways to get a chunk. A future completing with {@code null} is a different statement: the chunk is
 * genuinely not saved, which is an answer rather than a failure, and the caller resolves it against
 * the load policy (nothing to load, or terrain to generate).
 *
 * <p>Implementations must be safe to call from any thread and must not require the server thread:
 * the point of this path is that it never queues behind a tick.
 */
public interface OfflineChunkSource {

  /**
   * A source for a server whose internals Cobblestone cannot read — including, until its NMS
   * arrives, a version module that has not implemented one yet.
   *
   * <p>{@link #available()} is {@code false}, so {@link SpongeChunkLoader} never calls {@link
   * #read} and goes straight to a ticket. That is exactly the behavior Cobblestone had before any
   * offline reading existed: slower and heavier, and correct.
   */
  OfflineChunkSource UNAVAILABLE =
      new OfflineChunkSource() {
        @Override
        public boolean available() {
          return false;
        }

        @Override
        public CompletableFuture<@Nullable MinecraftChunk> read(
            ServerWorld world, int chunkX, int chunkZ, boolean urgent) {
          return CompletableFuture.completedFuture(null);
        }
      };

  /**
   * Returns whether this server actually has the internals this source reads.
   *
   * <p>Checked before every read so a source can retire itself mid-session when an internal turns
   * out to have moved, rather than failing every remaining chunk of every remaining search.
   *
   * @return {@code true} if {@link #read} is worth calling
   */
  boolean available();

  /**
   * Reads a chunk's saved blocks.
   *
   * @param world the world to read from
   * @param chunkX the chunk X
   * @param chunkZ the chunk Z
   * @param urgent whether a search is blocked on this chunk, as opposed to reading ahead
   * @return a future of the chunk, of {@code null} if nothing is saved there, or failed if the read
   *     could not be done at all
   */
  CompletableFuture<@Nullable MinecraftChunk> read(
      ServerWorld world, int chunkX, int chunkZ, boolean urgent);

  /**
   * Stops issuing reads and calls off whatever has not started, without waiting for what has.
   *
   * <p>Called at the start of shutdown. A source that queues nothing of its own has nothing to do.
   */
  default void shutdown() {}
}
