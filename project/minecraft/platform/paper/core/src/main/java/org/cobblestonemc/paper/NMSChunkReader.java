/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper;

import ca.spottedleaf.concurrentutil.executor.Cancellable;
import ca.spottedleaf.concurrentutil.util.Priority;
import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.minecraft.server.level.ServerLevel;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.cobblestonemc.CobblestoneLogger;
import org.jetbrains.annotations.Nullable;

/**
 * Issues offline chunk reads against the server's region IO, and keeps track of the ones still
 * outstanding.
 *
 * <p><b>Why it tracks them.</b> Reads are queued on the server's own IO scheduler, so at any moment
 * Cobblestone may owe the server a handful of file reads it asked for and no longer cares about.
 * Walking away from those at plugin disable is what makes a shutdown mid-search go badly: the reads
 * are still queued against region files the server is about to close. Holding the set of
 * outstanding reads costs one entry per read in flight — bounded by the search's own concurrency,
 * not by how much ground it covers — and it is what lets {@link #shutdown()} cancel what has not
 * started and let the rest finish before Cobblestone reports that it has stopped.
 *
 * <p>Note what is <em>not</em> tracked: the searches parked on these reads. A search abandons a
 * pending value when it is cancelled and never looks at it again, so the thing worth waiting on is
 * the IO, not the searcher.
 */
final class NMSChunkReader {

  private final CobblestoneLogger logger;

  /** Reads issued and not yet settled. Entries remove themselves as their futures complete. */
  private final Set<PendingRead> outstanding = ConcurrentHashMap.newKeySet();

  private volatile boolean stopped;

  NMSChunkReader(CobblestoneLogger logger) {
    this.logger = logger;
  }

  /**
   * Returns whether this server has the internals the offline read needs.
   *
   * @return {@code true} if {@link #read} can do anything useful
   */
  boolean available() {
    return NMSSupport.available(logger);
  }

  /**
   * Reads a chunk's saved blocks without loading the chunk.
   *
   * <p>The future <b>fails</b> if the chunk cannot be read — a region file that will not open, a
   * decode error, an internal that moved between server versions. That failure means only that the
   * <em>fast</em> path did not work, which is why it is reported rather than absorbed: {@link
   * PaperChunkFetcher} answers it by loading the chunk through the server instead, so nothing above
   * it ever learns there were two ways to get a chunk.
   *
   * <p>A chunk that simply is not there — never generated, or generated but not finished — is not a
   * failure. The future completes with {@code null}, which is the honest answer to "what blocks are
   * saved here" and stays off the exceptional path, because a search pressed against the edge of
   * generated terrain asks that question constantly.
   *
   * @param chunkX the chunk X
   * @param chunkZ the chunk Z
   * @param bukkitWorld the world to read from
   * @param urgent whether to jump the IO queue ahead of other background reads
   * @return a future of the chunk, {@code null} if nothing is saved there, or failed
   */
  CompletableFuture<@Nullable NMSChunk> read(
      int chunkX, int chunkZ, World bukkitWorld, boolean urgent) {
    CompletableFuture<NMSChunk> future = new CompletableFuture<>();
    PendingRead pending = new PendingRead(future);
    outstanding.add(pending);
    future.whenComplete((chunk, error) -> outstanding.remove(pending));
    if (stopped) {
      future.complete(null);
      return future;
    }

    try {
      ServerLevel level = ((CraftWorld) bukkitWorld).getHandle();
      // The callback can run before loadDataAsync returns, so `pending` is registered above and the
      // cancellation handle is attached after — never the other way round.
      Cancellable handle =
          MoonriseRegionFileIO.loadDataAsync(
              level,
              chunkX,
              chunkZ,
              MoonriseRegionFileIO.RegionFileType.CHUNK_DATA,
              (data, throwable) -> {
                if (throwable != null) {
                  future.completeExceptionally(throwable);
                } else if (data == null) {
                  future.complete(null); // never generated
                } else {
                  try {
                    future.complete(NMSChunk.parse(level, data));
                  } catch (Throwable error) {
                    future.completeExceptionally(error);
                  }
                }
              },
              false,
              urgent ? Priority.NORMAL : Priority.LOW);
      pending.handle = handle;
      if (stopped) {
        pending.cancel(); // shutdown began while this read was being queued
      }
    } catch (Throwable error) {
      future.completeExceptionally(error);
    }
    return future;
  }

  /**
   * Notes that a read failed, and decides whether the offline path stays open.
   *
   * <p>A {@link LinkageError} means an internal moved under us and every later read would fail
   * identically, so it closes the path for the session — after which {@link PaperChunkFetcher}
   * stops trying it at all. Anything else is an ordinary IO or decode failure that says nothing
   * about the server version, so it is logged sparingly and changes nothing. Either way the message
   * is rationed; see {@link NMSSupport}.
   *
   * @param error the failure, as a future handed it over (a completion wrapper is unwrapped here)
   * @param chunkX the chunk X
   * @param chunkZ the chunk Z
   * @param bukkitWorld the world that was being read
   */
  void reportFailure(Throwable error, int chunkX, int chunkZ, World bukkitWorld) {
    Throwable cause = unwrap(error);
    if (cause instanceof LinkageError linkage) {
      NMSSupport.reportLinkageFailure(logger, linkage);
      return;
    }
    NMSSupport.reportReadFailure(
        logger,
        "Could not read chunk [{}, {}] of world {} directly from disk; loading it through the"
            + " server instead, which is slower",
        cause,
        chunkX,
        chunkZ,
        bukkitWorld.getName());
  }

  /**
   * Strips the {@link CompletionException} a future wraps a failure in, so the cause can be tested
   * for what it actually is.
   */
  private static Throwable unwrap(Throwable error) {
    Throwable cause = error;
    while ((cause instanceof CompletionException || cause instanceof ExecutionException)
        && cause.getCause() != null) {
      cause = cause.getCause();
    }
    return cause;
  }

  /**
   * Stops issuing reads, cancels the ones the server has not started, and waits for the rest.
   *
   * <p>A cancelled read completes as though nothing were saved there, so it resolves to an unknown
   * chunk rather than falling back to a server load. That is the point: a server on its way down
   * should not start loading chunks for a search that is also on its way down.
   *
   * <p>The wait is bounded. Moonrise's IO threads may already be gone by the time this runs, in
   * which case the reads they held will never complete, and hanging on them would be worse than
   * giving up on a chunk nobody is going to use.
   *
   * @param timeoutMillis how long to wait for reads already underway
   */
  void shutdown(long timeoutMillis) {
    stopped = true;
    // Snapshot before cancelling: a cancelled read completes, which removes it from the set.
    CompletableFuture<?>[] pending =
        outstanding.stream().map(read -> read.future).toArray(CompletableFuture<?>[]::new);
    for (PendingRead read : outstanding) {
      read.cancel();
    }
    if (pending.length == 0) {
      return;
    }
    try {
      CompletableFuture.allOf(pending).get(timeoutMillis, TimeUnit.MILLISECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    } catch (TimeoutException timedOut) {
      logger.warn(
          "Gave up after {}ms waiting for {} outstanding chunk reads; shutting down anyway",
          timeoutMillis,
          pending.length);
    } catch (ExecutionException failed) {
      // A failed read is a settled one; nothing is waiting on it any more.
    }
  }

  /** One issued read: its future, and the handle that can still call it off. */
  private static final class PendingRead {

    private final CompletableFuture<NMSChunk> future;

    /** Written once, just after the read is queued; read by {@link #cancel()} from any thread. */
    private volatile @Nullable Cancellable handle;

    private PendingRead(CompletableFuture<NMSChunk> future) {
      this.future = future;
    }

    /**
     * Calls off the read if it has not started, completing the future so nothing waits on it.
     *
     * <p>A {@code false} from {@link Cancellable#cancel()} means the read is already running or
     * done and will complete the future itself, so there is nothing to do.
     */
    private void cancel() {
      Cancellable current = handle;
      if (current != null && current.cancel()) {
        future.complete(null);
      }
    }
  }
}
