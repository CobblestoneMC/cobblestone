/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.cobblestonemc.CobblestoneLogger;
import org.cobblestonemc.ScopedCobblestoneLogger;
import org.cobblestonemc.minecraft.MinecraftChunk;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.api.ResourceKey;
import org.spongepowered.api.world.server.ServerWorld;

/**
 * An {@link OfflineChunkSource} that reads a world's region files directly.
 *
 * <p><b>Why this rather than the server's own classes.</b> The obvious way to read a saved chunk is
 * to ask Minecraft, which already has region file readers, NBT, palettes and data fixers. Doing so
 * would tie this module to one Minecraft version — those classes are renamed every release — and a
 * separate module per supported version is a real ongoing cost. The saved format itself is not like
 * that: the region layout has been fixed since 1.13 and the chunk layout since 1.18, because worlds
 * have to stay readable. So Cobblestone reads the file, and asks <em>Sponge</em> what the blocks in
 * it mean — {@link SpongeBlocks} turns each palette entry into the same {@code BlockState} a loaded
 * chunk would yield. Nothing here names a {@code net.minecraft} type, and the same code serves
 * every Minecraft version Cobblestone supports.
 *
 * <p><b>What it gives up.</b> No data fixers, so a chunk saved by a Minecraft older than 1.18 is
 * declined rather than guessed at; and no knowledge of compressions added after the fact, so a
 * server configured for LZ4 region files retires this source instead of misreading them. Both are
 * answered by loading the chunk the ordinary way, which is what the rest of {@link
 * SpongeChunkLoader} was already for.
 *
 * <p>Reads run on a small pool of their own rather than the search workers: they are blocking file
 * IO, and a search thread parked on a disk read is a search thread not searching.
 */
public final class AnvilOfflineChunkSource implements OfflineChunkSource {

  /**
   * How many reads may be in flight.
   *
   * <p>Deliberately small. This is disk-bound work, and a chunk read is milliseconds of seek and
   * inflate, so more threads buy queueing rather than throughput — and every one of them is
   * competing with the server for the same disk.
   */
  private static final int READ_THREADS = 2;

  /** How long shutdown waits for reads already underway before answering them itself. */
  private static final long SHUTDOWN_DRAIN_MILLIS = 5_000L;

  /** The shortest gap between two read-failure messages; the rest are counted and folded in. */
  private static final long FAILURE_LOG_INTERVAL_MILLIS = 60_000L;

  private final CobblestoneLogger logger;
  private final SpongeBlocks blocks = new SpongeBlocks();
  private final ExecutorService readers;

  /** Region directories by world, so a read does not re-resolve a path it already knows. */
  private final Map<ResourceKey, Path> regionFolders = new ConcurrentHashMap<>();

  /** Reads issued and not yet settled, so shutdown can answer the ones it drops. */
  private final Set<CompletableFuture<MinecraftChunk>> outstanding = ConcurrentHashMap.newKeySet();

  private final AtomicLong lastFailureLoggedAt = new AtomicLong();
  private final AtomicLong suppressedFailures = new AtomicLong();

  private volatile boolean usable = true;
  private volatile boolean stopped;

  /**
   * Creates a source.
   *
   * @param logger the logger to report read failures to
   */
  public AnvilOfflineChunkSource(CobblestoneLogger logger) {
    this.logger = new ScopedCobblestoneLogger(logger, "AnvilOfflineChunkSource");
    AtomicInteger counter = new AtomicInteger();
    this.readers =
        Executors.newFixedThreadPool(
            READ_THREADS,
            runnable -> {
              Thread thread =
                  new Thread(runnable, "cobblestone-chunk-io-" + counter.incrementAndGet());
              thread.setDaemon(true);
              return thread;
            });
  }

  @Override
  public void prepare(ServerWorld world) {
    regionFolder(world);
  }

  @Override
  public boolean available() {
    return usable && !stopped;
  }

  @Override
  public CompletableFuture<@Nullable MinecraftChunk> read(
      ServerWorld world, int chunkX, int chunkZ, boolean urgent) {
    CompletableFuture<MinecraftChunk> future = new CompletableFuture<>();
    // Resolved here rather than on a reader thread, which must not touch the world at all. In
    // practice prepare() has already done it on the server thread and this is a map lookup.
    Path regionFolder = regionFolder(world);

    outstanding.add(future);
    future.whenComplete((chunk, error) -> outstanding.remove(future));
    try {
      readers.execute(
          () -> {
            if (stopped) {
              future.complete(null);
              return;
            }
            try {
              future.complete(load(regionFolder, chunkX, chunkZ));
            } catch (Throwable error) {
              reportFailure(error, chunkX, chunkZ, world);
              future.completeExceptionally(error);
            }
          });
    } catch (RejectedExecutionException shuttingDown) {
      future.complete(null);
    }
    return future;
  }

  private Path regionFolder(ServerWorld world) {
    return regionFolders.computeIfAbsent(
        world.key(), ignored -> world.directory().resolve("region"));
  }

  /** Reads and decodes one chunk, or {@code null} if nothing usable is saved there. */
  private @Nullable MinecraftChunk load(Path regionFolder, int chunkX, int chunkZ)
      throws IOException {
    Map<String, Object> tag = AnvilRegionFile.readChunk(regionFolder, chunkX, chunkZ);
    if (tag == null) {
      return null; // never generated
    }
    return AnvilChunk.decode(tag, blocks);
  }

  /**
   * Logs a read failure sparingly, and retires this source if the failure was about the format
   * rather than about one chunk.
   *
   * <p>A region file written with a compression Cobblestone cannot read is a property of the
   * server, not of the chunk: every later read would fail identically, so the source closes and
   * {@link SpongeChunkLoader} goes back to tickets for good. Anything else — a torn read of a chunk
   * the server was saving, a damaged file — says nothing about the next chunk, and changes nothing.
   */
  private void reportFailure(Throwable error, int chunkX, int chunkZ, ServerWorld world) {
    String message = error.getMessage();
    if (message != null && message.contains("which Cobblestone cannot read")) {
      if (usable) {
        usable = false;
        logger.warn(
            "This world's region files use a compression Cobblestone cannot read ({}), so unloaded"
                + " chunks will be loaded through the server instead — slower and heavier, but"
                + " correct. Setting region-file-compression to deflate restores the fast path."
                + " This message is not repeated.",
            message);
      }
      return;
    }

    long now = System.currentTimeMillis();
    long last = lastFailureLoggedAt.get();
    if (now - last < FAILURE_LOG_INTERVAL_MILLIS || !lastFailureLoggedAt.compareAndSet(last, now)) {
      suppressedFailures.incrementAndGet();
      return;
    }
    long suppressed = suppressedFailures.getAndSet(0);
    logger.error(
        "Could not read chunk [{}, {}] of world {} from disk; loading it through the server"
            + " instead, which is slower ({} further failures since the last message)",
        error,
        chunkX,
        chunkZ,
        world.key(),
        suppressed);
  }

  /**
   * Stops issuing reads, waits briefly for the one or two underway, and answers the rest.
   *
   * <p>The wait is bounded: a read stuck on a disk that is not answering must not hold the server
   * open. Whatever is still queued when the pool stops will never run, and something may be parked
   * on it, so those are answered as "nothing there" rather than left hanging.
   */
  @Override
  public void shutdown() {
    stopped = true;
    readers.shutdownNow();
    try {
      readers.awaitTermination(SHUTDOWN_DRAIN_MILLIS, TimeUnit.MILLISECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
    for (CompletableFuture<MinecraftChunk> pending : outstanding) {
      pending.complete(null);
    }
  }
}
