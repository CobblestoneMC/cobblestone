/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.lod;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.cobblestonemc.minecraft.MinecraftChunk;
import org.cobblestonemc.minecraft.MinecraftWorld;
import org.jetbrains.annotations.Nullable;

/**
 * A server's {@link RegionStore}s, one per world, and the threads they share.
 *
 * <p>The platform's one handle on the region layer: searches ask it for their world's store,
 * block-change listeners tell it what to forget, chunk loads offer it terrain to build ahead of
 * time, and a timer and shutdown tell it to write.
 *
 * <p><b>Two threads of its own kinds.</b> One for the disk, so reads and writes of the same files
 * never race; and a small pool for building, so a chunk that arrives on the server thread is not
 * built there. Both are daemons, and {@link #close} drains them.
 */
public final class RegionStores implements AutoCloseable {

  private final @Nullable Path root;
  private final long memoryBudget;
  private final double detourRatio;
  private final ExecutorService disk;
  private final ExecutorService builds;
  private final ThreadPoolExecutor prebuilds;
  private final Map<String, RegionStore> stores = new ConcurrentHashMap<>();

  /**
   * Creates the registry.
   *
   * @param root where to keep each world's files, under a directory per world; {@code null} to keep
   *     columns in memory only, shared between searches but lost on restart
   * @param memoryBudget how many bytes of columns each world keeps in memory
   * @param builderThreads how many threads build columns from chunks that arrive later
   */
  public RegionStores(@Nullable Path root, long memoryBudget, int builderThreads) {
    this.root = root;
    this.memoryBudget = memoryBudget;
    this.detourRatio = RegionGraph.DEFAULT_DETOUR_RATIO;
    this.disk = Executors.newSingleThreadExecutor(daemons("cobblestone-regions-disk"));
    this.builds =
        Executors.newFixedThreadPool(
            Math.max(1, builderThreads), daemons("cobblestone-regions-build"));
    // Bounded, and dropping what does not fit: a player flying fast loads chunks faster than one
    // thread can build them, and a queue that grew without limit would be memory and stale work.
    // A dropped chunk costs nothing but being built on demand later.
    this.prebuilds =
        new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(PREBUILD_QUEUE),
            daemons("cobblestone-regions-prebuild"),
            new ThreadPoolExecutor.DiscardPolicy());
  }

  /** Chunks waiting to be built ahead of time, at most. */
  private static final int PREBUILD_QUEUE = 512;

  /**
   * Returns a world's store, creating it on first use.
   *
   * @param world the world
   * @return its store
   */
  public RegionStore of(MinecraftWorld world) {
    return stores.computeIfAbsent(
        world.key(),
        key -> {
          RegionFiles files =
              root == null
                  ? null
                  : new RegionFiles(
                      root.resolve(RegionFiles.directoryName(key)),
                      RegionColumnBuilder.columnMinY(world.minY()),
                      RegionColumnBuilder.sections(world.minY(), world.maxY()),
                      detourRatio);
          return new RegionStore(
              world.minY(), world.maxY(), detourRatio, memoryBudget, files, disk, builds);
        });
  }

  /**
   * Forgets a column because a block in it changed. Cheap enough for every block event.
   *
   * @param world the world
   * @param chunkX the chunk X
   * @param chunkZ the chunk Z
   */
  public void invalidate(MinecraftWorld world, int chunkX, int chunkZ) {
    of(world).invalidate(chunkX, chunkZ);
  }

  /**
   * Marks a chunk as loaded by the server, so its column stays in memory while it is.
   *
   * @param world the world
   * @param chunkX the chunk X
   * @param chunkZ the chunk Z
   */
  public void chunkLoaded(MinecraftWorld world, int chunkX, int chunkZ) {
    of(world).chunkLoaded(chunkX, chunkZ);
  }

  /**
   * Marks a chunk as unloaded, so its column is kept only while the memory budget allows.
   *
   * @param world the world
   * @param chunkX the chunk X
   * @param chunkZ the chunk Z
   */
  public void chunkUnloaded(MinecraftWorld world, int chunkX, int chunkZ) {
    of(world).chunkUnloaded(chunkX, chunkZ);
  }

  /**
   * Builds a column ahead of time from a chunk the server has loaded anyway, on the build pool.
   *
   * @param world the world
   * @param chunkX the chunk X
   * @param chunkZ the chunk Z
   * @param chunk the chunk; it must stay readable from another thread
   */
  public void prebuild(MinecraftWorld world, int chunkX, int chunkZ, MinecraftChunk chunk) {
    RegionStore store = of(world);
    prebuilds.execute(() -> store.prebuild(chunkX, chunkZ, chunk));
  }

  /**
   * Returns whether a column is in hand or on its way, so a caller can skip fetching its chunk to
   * prebuild it. Says nothing about disk.
   *
   * @param world the world
   * @param chunkX the chunk X
   * @param chunkZ the chunk Z
   * @return {@code true} if building it now would be wasted
   */
  public boolean known(MinecraftWorld world, int chunkX, int chunkZ) {
    return of(world).known(chunkX, chunkZ);
  }

  /**
   * Returns whether there is room to queue another prebuild. When there is not, an offered chunk is
   * skipped rather than queued: it is built when a search asks for it instead.
   *
   * @return {@code true} if a prebuild offered now would be queued
   */
  public boolean prebuildRoom() {
    return prebuilds.getQueue().remainingCapacity() > 0;
  }

  /**
   * Writes every world's unwritten columns.
   *
   * @return a future completing when every write is done
   */
  public CompletableFuture<Void> flush() {
    List<CompletableFuture<Void>> writes = new ArrayList<>();
    for (RegionStore store : stores.values()) {
      writes.add(store.flush());
    }
    return CompletableFuture.allOf(writes.toArray(new CompletableFuture<?>[0]));
  }

  /**
   * Returns every world's store, by world key.
   *
   * @return the stores
   */
  public Map<String, RegionStore> stores() {
    return Map.copyOf(stores);
  }

  /**
   * Stops building, writes what is unwritten, and stops the disk thread.
   *
   * <p>Builds still running are let finish for a moment, so their columns are written too.
   */
  @Override
  public void close() {
    prebuilds.shutdown();
    builds.shutdown();
    try {
      prebuilds.awaitTermination(5, TimeUnit.SECONDS);
      builds.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
    try {
      flush().get(30, TimeUnit.SECONDS);
    } catch (Exception failed) {
      // Unwritten columns are rebuilt after the restart; nothing is lost but time.
    }
    for (RegionStore store : stores.values()) {
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
      // Below the server's own threads: this is work done ahead of time, never on a tick.
      thread.setPriority(Thread.NORM_PRIORITY - 1);
      return thread;
    };
  }
}
