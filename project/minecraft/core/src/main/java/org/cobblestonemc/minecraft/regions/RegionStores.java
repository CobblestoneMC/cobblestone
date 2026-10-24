/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.regions;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.cobblestonemc.Cell;
import org.cobblestonemc.minecraft.MinecraftChunk;
import org.cobblestonemc.minecraft.MinecraftWorld;
import org.cobblestonemc.regions.RegionSpec;
import org.cobblestonemc.regions.RegionStore;

/**
 * A server's {@link RegionStore}s, one per world, and the threads they share.
 *
 * <p>The platform's one handle on the region layer: searches ask it for their world's store,
 * block-change listeners tell it what to forget, and trips tell it whose surroundings to keep.
 *
 * <p><b>Navigating players' surroundings stay in memory.</b> A trip's searches pin the chunks
 * within {@link #PIN_RADIUS} of where they start, under the player's id, so the short searches that
 * re-plan around a navigating player never build; the pin follows the player as each new search
 * starts from where they are, and is released when their last trip ends. Everything else is kept
 * within the memory budget, least recently used out.
 *
 * <p><b>Changes are forgotten in batches.</b> A block change marks its column, and the column is
 * forgotten {@link #INVALIDATION_DELAY} later, however many more changes it had meanwhile: a farm
 * or a flowing river would otherwise have its columns rebuilt for every search that passes. A
 * search in that window reads the column as it was, which costs the estimate a little and never the
 * route, since the fine search reads the blocks themselves.
 *
 * <p>Builds and those delays run on a small pool of daemon threads, so a chunk that arrives on the
 * server thread is not built there.
 */
public final class RegionStores implements AutoCloseable {

  /**
   * How many chunks out from a navigating player their pin reaches: 4 holds 9×9 chunks, about 3 MB.
   */
  public static final int PIN_RADIUS = 4;

  /** How long after a block changes its column is forgotten, with every change meanwhile. */
  public static final Duration INVALIDATION_DELAY = Duration.ofSeconds(10);

  private final long memoryBudget;
  private final Duration invalidationDelay;
  private final ScheduledExecutorService threads;
  private final Map<String, RegionStore<MinecraftChunk>> stores = new ConcurrentHashMap<>();

  /** Columns marked as changed and waiting to be forgotten. */
  private final Set<Changed> changed = ConcurrentHashMap.newKeySet();

  private record Changed(String world, int chunkX, int chunkZ) {}

  /**
   * Creates the registry.
   *
   * @param memoryBudget how many bytes of columns not pinned each world keeps in memory
   * @param builderThreads how many threads build columns from chunks that arrive later
   */
  public RegionStores(long memoryBudget, int builderThreads) {
    this(memoryBudget, builderThreads, INVALIDATION_DELAY);
  }

  RegionStores(long memoryBudget, int builderThreads, Duration invalidationDelay) {
    this.memoryBudget = memoryBudget;
    this.invalidationDelay = invalidationDelay;
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
  public RegionStore<MinecraftChunk> of(MinecraftWorld world) {
    return stores.computeIfAbsent(
        world.key(), key -> new RegionStore<>(MinecraftTerrain.spec(world), memoryBudget, threads));
  }

  /**
   * Marks a column as changed, to be forgotten after {@link #INVALIDATION_DELAY} with every other
   * change it has meanwhile. Cheap enough for every block event.
   *
   * @param world the world
   * @param chunkX the chunk X
   * @param chunkZ the chunk Z
   */
  public void invalidate(MinecraftWorld world, int chunkX, int chunkZ) {
    Changed column = new Changed(world.key(), chunkX, chunkZ);
    if (!changed.add(column)) {
      return; // already waiting: this change is forgotten with it
    }
    threads.schedule(() -> forget(column), invalidationDelay.toNanos(), TimeUnit.NANOSECONDS);
  }

  private void forget(Changed column) {
    // Unmarked first, so a change that lands while the column is forgotten marks it again.
    changed.remove(column);
    RegionStore<MinecraftChunk> store = stores.get(column.world());
    if (store != null) {
      store.invalidate(column.chunkX(), column.chunkZ());
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
    for (Map.Entry<String, RegionStore<MinecraftChunk>> entry : stores.entrySet()) {
      if (!entry.getKey().equals(world.key())) {
        entry.getValue().release(player);
      }
    }
    RegionStore<MinecraftChunk> store = of(world);
    RegionSpec<MinecraftChunk> spec = store.spec();
    store.pin(player, spec.columnOf(at.x()), spec.columnOf(at.z()), PIN_RADIUS);
  }

  /**
   * Lets a player's surroundings go, once they have stopped navigating.
   *
   * @param player the player's id
   */
  public void release(UUID player) {
    for (RegionStore<MinecraftChunk> store : stores.values()) {
      store.release(player);
    }
  }

  /**
   * Drops a world's store, once the world has unloaded.
   *
   * @param worldKey the world's key
   */
  public void worldUnloaded(String worldKey) {
    stores.remove(worldKey);
  }

  /** Stops building, letting builds still running finish for a moment; pending changes are moot. */
  @Override
  public void close() {
    threads.shutdown();
    try {
      threads.awaitTermination(5, TimeUnit.SECONDS);
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
