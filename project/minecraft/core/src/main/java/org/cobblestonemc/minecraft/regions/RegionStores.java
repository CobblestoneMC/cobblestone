/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.regions;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.cobblestonemc.Cell;
import org.cobblestonemc.CobblestoneLogger;
import org.cobblestonemc.minecraft.MinecraftChunk;
import org.cobblestonemc.minecraft.MinecraftWorld;
import org.cobblestonemc.regions.RegionFiles;
import org.cobblestonemc.regions.RegionSpec;
import org.cobblestonemc.regions.RegionStore;
import org.jetbrains.annotations.Nullable;

/**
 * A server's {@link RegionStore}s, one per world, and the threads they share.
 *
 * <p>The platform's one handle on the region layer: searches ask it for their world's store,
 * block-change listeners tell it what to forget, trips tell it whose surroundings to keep, and a
 * timer, world saves and shutdown tell it to write, and an admin tells it to start a world over.
 *
 * <p><b>Navigating players' surroundings stay in memory.</b> A trip's searches pin the sections
 * within {@link #PIN_RADIUS} of where they start, under the player's id, so the short searches that
 * re-plan around a navigating player never build; the pin follows the player as each new search
 * starts from where they are, and is released when their last trip ends. Everything else is kept
 * within the memory budget, least recently used out.
 *
 * <p><b>Changes are forgotten in batches.</b> A block change marks the sections whose classes read
 * it, and each is forgotten {@link #INVALIDATION_DELAY} later, however many more changes it had
 * meanwhile: a farm or a flowing river would otherwise have its sections rebuilt for every search
 * that passes. A search in that window reads the section as it was, which costs the estimate a
 * little and never the route, since the fine search reads the blocks themselves.
 *
 * <p><b>Two kinds of thread of its own.</b> One for the disk, so reads and writes of the same files
 * never race; and a small pool for building and those delays, so a chunk that arrives on the server
 * thread is not built there. Both are daemons, and {@link #close} drains them.
 */
public final class RegionStores implements AutoCloseable {

  /**
   * How many sections out from a navigating player their pin reaches, along every axis: 4 holds a
   * cube of 9×9×9 sections, 144 blocks a side.
   */
  public static final int PIN_RADIUS = 4;

  /** How long after a block changes its sections are forgotten, with every change meanwhile. */
  public static final Duration INVALIDATION_DELAY = Duration.ofSeconds(10);

  private final @Nullable Path root;
  private final CobblestoneLogger log;
  private final long memoryBudget;
  private final Duration invalidationDelay;
  private final ExecutorService disk;
  private final ScheduledExecutorService threads;
  private final Map<String, RegionStore<MinecraftChunk, RegionClass>> stores =
      new ConcurrentHashMap<>();

  /** Sections marked as changed and waiting to be forgotten. */
  private final Set<Changed> changed = ConcurrentHashMap.newKeySet();

  private record Changed(String world, int sectionX, int sectionY, int sectionZ) {}

  /** Worlds whose last write failed: said once, and again once they write. */
  private final Set<String> failing = ConcurrentHashMap.newKeySet();

  /**
   * Creates the registry.
   *
   * @param root where to keep each world's files, under a directory per world; {@code null} to keep
   *     sections in memory only, shared between searches but lost on restart
   * @param memoryBudget how many bytes of sections not pinned each world keeps in memory
   * @param builderThreads how many threads build sections from chunks that arrive later
   * @param log where a write that fails is told
   */
  public RegionStores(
      @Nullable Path root, long memoryBudget, int builderThreads, CobblestoneLogger log) {
    this(root, memoryBudget, builderThreads, log, INVALIDATION_DELAY);
  }

  RegionStores(
      @Nullable Path root,
      long memoryBudget,
      int builderThreads,
      CobblestoneLogger log,
      Duration invalidationDelay) {
    this.root = root;
    this.log = log;
    this.memoryBudget = memoryBudget;
    this.invalidationDelay = invalidationDelay;
    this.disk = Executors.newSingleThreadExecutor(daemons("cobblestone-regions-disk"));
    ScheduledThreadPoolExecutor pool =
        new ScheduledThreadPoolExecutor(
            Math.max(1, builderThreads), daemons("cobblestone-regions"));
    // A change still waiting at shutdown has nothing left to be forgotten from.
    pool.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    this.threads = pool;
  }

  /**
   * Returns a world's store, creating it on first use.
   *
   * @param world the world
   * @return its store
   */
  public RegionStore<MinecraftChunk, RegionClass> of(MinecraftWorld world) {
    return stores.computeIfAbsent(
        world.key(),
        key -> {
          RegionSpec<MinecraftChunk, RegionClass> spec = MinecraftTerrain.spec(world);
          return new RegionStore<>(
              spec,
              memoryBudget,
              root == null ? null : new RegionFiles(directory(key), spec, world.seed()),
              disk,
              threads);
        });
  }

  private Path directory(String worldKey) {
    return root.resolve(RegionFiles.directoryName(worldKey));
  }

  /**
   * Marks the sections a changed block is read by as changed, to be forgotten after {@link
   * #INVALIDATION_DELAY} with every other change they have meanwhile: its own, and the one above or
   * below where the block is on its section's edge, since a class reads the blocks over and under
   * it. Cheap enough for every block event.
   *
   * @param world the world
   * @param x the block's X
   * @param y the block's Y
   * @param z the block's Z
   */
  public void invalidate(MinecraftWorld world, int x, int y, int z) {
    RegionSpec<MinecraftChunk, RegionClass> spec = of(world).spec();
    int sectionX = spec.sectionOf(x);
    int sectionZ = spec.sectionOf(z);
    for (int dy = -1; dy <= 1; dy++) {
      mark(new Changed(world.key(), sectionX, spec.sectionOf(y + dy), sectionZ));
    }
  }

  private void mark(Changed section) {
    if (!changed.add(section)) {
      return; // already waiting: this change is forgotten with it
    }
    threads.schedule(() -> forget(section), invalidationDelay.toNanos(), TimeUnit.NANOSECONDS);
  }

  private void forget(Changed section) {
    // Unmarked first, so a change that lands while the section is forgotten marks it again.
    changed.remove(section);
    RegionStore<MinecraftChunk, RegionClass> store = stores.get(section.world());
    if (store != null) {
      store.invalidate(section.sectionX(), section.sectionY(), section.sectionZ());
    }
  }

  /**
   * Keeps a navigating player's surroundings in memory, moving any pin they held before.
   *
   * @param player the player's id
   * @param world the world they are in
   * @param at where they are
   */
  public void pin(UUID player, MinecraftWorld world, Cell at) {
    // A pin in another world first lets go of the last one: a player is in one world at a time.
    for (Map.Entry<String, RegionStore<MinecraftChunk, RegionClass>> entry : stores.entrySet()) {
      if (!entry.getKey().equals(world.key())) {
        entry.getValue().release(player);
      }
    }
    RegionStore<MinecraftChunk, RegionClass> store = of(world);
    RegionSpec<MinecraftChunk, RegionClass> spec = store.spec();
    store.pin(
        player, spec.sectionOf(at.x()), spec.sectionOf(at.y()), spec.sectionOf(at.z()), PIN_RADIUS);
  }

  /**
   * Lets a player's surroundings go, once they have stopped navigating.
   *
   * @param player the player's id
   */
  public void release(UUID player) {
    for (RegionStore<MinecraftChunk, RegionClass> store : stores.values()) {
      store.release(player);
    }
  }

  /**
   * Drops a world's store, once the world has unloaded, writing what it has not written.
   *
   * @param worldKey the world's key
   */
  public void worldUnloaded(String worldKey) {
    RegionStore<MinecraftChunk, RegionClass> store = stores.remove(worldKey);
    if (store != null) {
      written(worldKey, store.flush());
      // Queued right behind the write on the one disk thread, not once it completes: anything
      // queued after, such as a clear or a store made for the world afresh, finds the files closed.
      store.close();
    }
  }

  /**
   * Writes every world's unwritten sections.
   *
   * @return a future completing when every write is done
   */
  public CompletableFuture<Void> flush() {
    List<CompletableFuture<Void>> writes = new ArrayList<>();
    for (Map.Entry<String, RegionStore<MinecraftChunk, RegionClass>> entry : stores.entrySet()) {
      writes.add(written(entry.getKey(), entry.getValue().flush()));
    }
    return CompletableFuture.allOf(writes.toArray(new CompletableFuture<?>[0]));
  }

  /**
   * Writes one world's unwritten sections, as when it saves.
   *
   * @param worldKey the world's key
   * @return a future completing when the write is done
   */
  public CompletableFuture<Void> flush(String worldKey) {
    RegionStore<MinecraftChunk, RegionClass> store = stores.get(worldKey);
    return store == null
        ? CompletableFuture.completedFuture(null)
        : written(worldKey, store.flush());
  }

  /**
   * Forgets every section of a world, in memory and on disk, so each is built again from the
   * terrain: for terrain that changed where no event said so, such as by an external editor or a
   * world trimmed or made again from its seed. Searches already running keep what they hold.
   *
   * @param worldKey the world's key, loaded or not
   * @return a future completing once its files are deleted
   */
  public CompletableFuture<Void> clear(String worldKey) {
    List<CompletableFuture<Void>> deleting = new ArrayList<>(1);
    // Under the map's lock for the world, so a store made for it afresh is made after the delete
    // is queued, and reads on the disk thread only once the files are gone.
    stores.compute(
        worldKey,
        (key, store) -> {
          if (store != null) {
            deleting.add(store.delete());
          } else if (root != null) {
            deleting.add(
                CompletableFuture.runAsync(
                    () -> {
                      try {
                        RegionFiles.delete(directory(key));
                      } catch (IOException failed) {
                        throw new UncheckedIOException(failed);
                      }
                    },
                    disk));
          }
          return null;
        });
    return deleting.isEmpty() ? CompletableFuture.completedFuture(null) : deleting.get(0);
  }

  /** Says when a world's writes start failing, and when they recover. */
  private CompletableFuture<Void> written(String worldKey, CompletableFuture<Void> write) {
    return write.whenComplete(
        (done, failed) -> {
          if (failed != null) {
            if (failing.add(worldKey)) {
              log.error(
                  "Could not write the regions of {}; they are built again when needed, and"
                      + " writing is tried again at each flush",
                  failed,
                  worldKey);
            }
          } else if (failing.remove(worldKey)) {
            log.info("Writing the regions of {} again", worldKey);
          }
        });
  }

  /**
   * Stops building, writes what is unwritten, and stops the disk thread. Changes still waiting to
   * be forgotten are moot.
   *
   * <p>Builds still running are let finish for a moment, so their sections are written too. Call it
   * after the searches have stopped, so nothing builds a section behind the final write.
   */
  @Override
  public void close() {
    threads.shutdown();
    try {
      threads.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
    try {
      flush().get(30, TimeUnit.SECONDS);
    } catch (Exception failed) {
      // Unwritten sections are built again after the restart; nothing is lost but time.
    }
    for (RegionStore<MinecraftChunk, RegionClass> store : stores.values()) {
      store.close();
    }
    disk.shutdown();
    try {
      disk.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private static ThreadFactory daemons(String name) {
    AtomicInteger count = new AtomicInteger();
    return runnable -> {
      Thread thread = new Thread(runnable, name + "-" + count.incrementAndGet());
      thread.setDaemon(true);
      // Below the server's own threads: this is work a search waits on, never on a tick.
      thread.setPriority(Thread.NORM_PRIORITY - 1);
      return thread;
    };
  }
}
