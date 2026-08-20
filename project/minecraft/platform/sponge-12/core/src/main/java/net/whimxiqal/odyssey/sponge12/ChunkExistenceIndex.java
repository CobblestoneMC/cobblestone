/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.sponge12;

import java.util.BitSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import net.whimxiqal.odyssey.OdysseyLogger;
import org.spongepowered.api.ResourceKey;
import org.spongepowered.api.world.server.ServerWorld;
import org.spongepowered.math.vector.Vector3i;

/**
 * Remembers which chunks already exist on disk, per world, so Odyssey can tell "load this chunk"
 * apart from "generate this chunk" before it asks for either.
 *
 * <p>Sponge's chunk-loading tickets make no such distinction: a ticket drives a chunk to full
 * status, generating it if it does not exist. Without knowing in advance, {@code allow_load} could
 * not be honored — the only safe policies would be "never load anything" and "generate whatever a
 * search wanders into". This index is what makes the middle policy real.
 *
 * <p><b>How it is built.</b> {@link ServerWorld#chunkPositions()} streams the positions actually
 * present in the world's region files. That stream is documented as slow and must be fully consumed
 * or closed (it holds region-file handles), so each world is scanned once, off-thread, on first
 * use. Until a scan finishes, positions read back {@link Presence#UNKNOWN} and callers treat that
 * as "do not load" — the conservative answer is the one you get during the gap.
 *
 * <p><b>Memory.</b> Positions are stored as one bit per chunk in a {@link BitSet} per 32&times;32
 * region, so a world of a million chunks costs on the order of a hundred kilobytes rather than the
 * tens of megabytes a set of boxed longs would.
 *
 * <p><b>Freshness.</b> New terrain appears through {@link #markPresent}, driven by Sponge's chunk
 * generation event and by every chunk Odyssey successfully reads. Chunks that exist only in memory
 * and have never been saved are not in the region files, but they are by definition loaded, and a
 * loaded chunk never reaches this index — it is served before the question is asked.
 */
final class ChunkExistenceIndex {

  /** Whether a chunk is known to exist in a world's region files. */
  enum Presence {
    /** The chunk exists on disk; loading it will not generate terrain. */
    PRESENT,
    /** The chunk is not in the region files; loading it would generate terrain. */
    ABSENT,
    /** The world has not been scanned yet (or the scan failed); treat as "do not load". */
    UNKNOWN
  }

  private final SpongeScheduler scheduler;
  private final OdysseyLogger logger;
  private final Map<ResourceKey, WorldIndex> worlds = new ConcurrentHashMap<>();

  ChunkExistenceIndex(SpongeScheduler scheduler, OdysseyLogger logger) {
    this.scheduler = scheduler;
    this.logger = logger;
  }

  /**
   * Returns whether a chunk exists on disk, starting the world's scan if it has not begun.
   *
   * @param world the world
   * @param chunkX the chunk x coordinate
   * @param chunkZ the chunk z coordinate
   * @return the chunk's presence, or {@link Presence#UNKNOWN} while the scan is in flight
   */
  Presence presence(ServerWorld world, int chunkX, int chunkZ) {
    WorldIndex index =
        worlds.computeIfAbsent(
            world.key(),
            key -> {
              WorldIndex created = new WorldIndex();
              scheduler.runAsync(() -> scan(key, world, created));
              return created;
            });
    return index.presence(chunkX, chunkZ);
  }

  /**
   * Records that a chunk now exists, so a later search does not mistake it for terrain that would
   * have to be generated. Safe to call from any thread, and before the world's scan completes.
   *
   * @param world the world's key
   * @param chunkX the chunk x coordinate
   * @param chunkZ the chunk z coordinate
   */
  void markPresent(ResourceKey world, int chunkX, int chunkZ) {
    WorldIndex index = worlds.get(world);
    if (index != null) {
      index.set(chunkX, chunkZ);
    }
  }

  /** Consumes one world's chunk-position stream into its index. Runs off the server thread. */
  private void scan(ResourceKey key, ServerWorld world, WorldIndex index) {
    long started = System.currentTimeMillis();
    int count = 0;
    // The stream holds region-file handles: consume it fully inside the try, never break out.
    try (Stream<Vector3i> positions = world.chunkPositions()) {
      for (Vector3i position : (Iterable<Vector3i>) positions::iterator) {
        index.set(position.x(), position.z());
        count++;
      }
    } catch (Exception e) {
      index.failed();
      logger.error(
          "Could not index the existing chunks of world '{}'. Odyssey will not load unloaded"
              + " chunks there, because it cannot tell which of them would have to be generated"
              + " first.",
          e,
          key);
      return;
    }
    index.ready();
    logger.debug(
        "Indexed {} existing chunks in world '{}' in {}ms",
        count,
        key,
        System.currentTimeMillis() - started);
  }

  /** One world's chunk-presence bitmap, keyed by region. */
  private static final class WorldIndex {

    private final Map<Long, BitSet> regions = new ConcurrentHashMap<>();
    private volatile boolean scanned;
    private volatile boolean broken;

    private Presence presence(int chunkX, int chunkZ) {
      if (broken) {
        return Presence.UNKNOWN;
      }
      BitSet region = regions.get(regionKey(chunkX, chunkZ));
      if (region != null) {
        synchronized (region) {
          if (region.get(bitIndex(chunkX, chunkZ))) {
            return Presence.PRESENT; // known present even mid-scan
          }
        }
      }
      // A missing bit only means "absent" once the whole world has been walked.
      return scanned ? Presence.ABSENT : Presence.UNKNOWN;
    }

    private void set(int chunkX, int chunkZ) {
      BitSet region =
          regions.computeIfAbsent(regionKey(chunkX, chunkZ), ignored -> new BitSet(1024));
      synchronized (region) {
        region.set(bitIndex(chunkX, chunkZ));
      }
    }

    private void ready() {
      this.scanned = true;
    }

    private void failed() {
      this.broken = true;
    }

    /** Region files hold 32&times;32 chunks; one {@link BitSet} covers one region. */
    private static long regionKey(int chunkX, int chunkZ) {
      return ((long) (chunkX >> 5) << 32) | ((chunkZ >> 5) & 0xFFFFFFFFL);
    }

    private static int bitIndex(int chunkX, int chunkZ) {
      return ((chunkZ & 31) << 5) | (chunkX & 31);
    }
  }
}
