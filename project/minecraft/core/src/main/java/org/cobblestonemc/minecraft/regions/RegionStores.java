/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.regions;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.cobblestonemc.minecraft.MinecraftChunk;
import org.cobblestonemc.minecraft.MinecraftWorld;
import org.cobblestonemc.regions.RegionFiles;
import org.cobblestonemc.regions.RegionStore;
import org.jetbrains.annotations.Nullable;

/**
 * A server's {@link RegionStore}s, one per world, and the threads they share.
 *
 * <p>The platform's one handle on the region layer: searches ask it for their world's store,
 * block-change listeners tell it what to forget, trips tell it whose surroundings to keep, and a
 * timer, world saves and shutdown tell it to write.
 *
 * <p><b>Navigating players' surroundings stay in memory.</b> A trip's searches pin the chunks
 * within {@link #PIN_RADIUS} of where they start, under the player's id, so the short searches that
 * re-plan around a navigating player never build; the pin follows the player as each new search
 * starts from where they are, and is released when their last trip ends. Everything else is kept
 * within the memory budget, least recently used out.
 *
 * <p><b>Two kinds of thread of its own.</b> One for the disk, so reads and writes of the same files
 * never race; and a small pool for building, so a chunk that arrives on the server thread is not
 * built there. Both are daemons, and {@link #close} drains them.
 */
public final class RegionStores implements AutoCloseable {

  /**
   * How many chunks out from a navigating player their pin reaches: 4 holds 9×9 chunks, about 3 MB.
   */
  public static final int PIN_RADIUS = 4;

  private final @Nullable Path root;
  private final long memoryBudget;
  private final ExecutorService disk;
  private final ExecutorService builds;
  private final Map<String, RegionStore<MinecraftChunk>> stores = new ConcurrentHashMap<>();

  /**
   * Creates the registry.
   *
   * @param root where to keep each world's files, under a directory per world; {@code null} to keep
   *     columns in memory only, shared between searches but lost on restart
   * @param memoryBudget how many bytes of columns not pinned each world keeps in memory
   * @param builderThreads how many threads build columns from chunks that arrive later
   */
  public RegionStores(@Nullable Path root, long memoryBudget, int builderThreads) {
    this.root = root;
    this.memoryBudget = memoryBudget;
    this.disk = Executors.newSingleThreadExecutor(daemons("cobblestone-regions-disk"));
    this.builds =
        Executors.newFixedThreadPool(
            Math.max(1, builderThreads), daemons("cobblestone-regions-build"));
  }

  /**
   * Returns a world's store, creating it on first use.
   *
   * @param world the world
   * @return its store
   */
  public RegionStore<MinecraftChunk> of(MinecraftWorld world) {
    return stores.computeIfAbsent(
        world.key(),
        key ->
            new RegionStore<>(
                MinecraftTerrain.spec(world),
                memoryBudget,
                root == null ? null : root.resolve(RegionFiles.directoryName(key)),
                disk,
                builds));
  }

  /**
   * Forgets a column because a block in it changed. Cheap enough for every block event.
   *
   * @param world the world
   * @param chunkX the chunk X
   * @param chunkZ the chunk Z
   */
  public void invalidate(MinecraftWorld world, int chunkX, int chunkZ) {
    RegionStore<MinecraftChunk> store = stores.get(world.key());
    if (store != null) {
      store.invalidate(chunkX, chunkZ);
    }
  }

  /**
   * Keeps a navigating player's surroundings in memory, moving any pin they held before.
   *
   * @param player the player's id
   * @param world the world they are in
   * @param chunkX the chunk X they are in
   * @param chunkZ the chunk Z they are in
   */
  public void pin(UUID player, MinecraftWorld world, int chunkX, int chunkZ) {
    // A pin in another world first lets go of the last one: a player is in one world at a time.
    for (Map.Entry<String, RegionStore<MinecraftChunk>> entry : stores.entrySet()) {
      if (!entry.getKey().equals(world.key())) {
        entry.getValue().release(player);
      }
    }
    of(world).pin(player, chunkX, chunkZ, PIN_RADIUS);
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
   * Drops a world's store, once the world has unloaded, writing what it has not written.
   *
   * @param worldKey the world's key
   */
  public void worldUnloaded(String worldKey) {
    RegionStore<MinecraftChunk> store = stores.remove(worldKey);
    if (store != null) {
      store.flush().whenComplete((written, failed) -> store.close());
    }
  }

  /**
   * Writes every world's unwritten columns.
   *
   * @return a future completing when every write is done
   */
  public CompletableFuture<Void> flush() {
    List<CompletableFuture<Void>> writes = new ArrayList<>();
    for (RegionStore<MinecraftChunk> store : stores.values()) {
      writes.add(store.flush());
    }
    return CompletableFuture.allOf(writes.toArray(new CompletableFuture<?>[0]));
  }

  /**
   * Returns every world's store, by world key.
   *
   * @return the stores
   */
  public Map<String, RegionStore<MinecraftChunk>> stores() {
    return Map.copyOf(stores);
  }

  /**
   * Stops building, writes what is unwritten, and stops the disk thread.
   *
   * <p>Builds still running are let finish for a moment, so their columns are written too. Call it
   * after the searches have stopped, so nothing builds a column behind the final write.
   */
  @Override
  public void close() {
    builds.shutdown();
    try {
      builds.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
    try {
      flush().get(30, TimeUnit.SECONDS);
    } catch (Exception failed) {
      // Unwritten columns are built again after the restart; nothing is lost but time.
    }
    for (RegionStore<MinecraftChunk> store : stores.values()) {
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
