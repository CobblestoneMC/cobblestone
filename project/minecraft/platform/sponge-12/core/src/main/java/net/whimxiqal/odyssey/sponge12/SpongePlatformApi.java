/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.sponge12;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.whimxiqal.odyssey.minecraft.ChunkLoadPolicy;
import net.whimxiqal.odyssey.minecraft.MinecraftChunk;
import net.whimxiqal.odyssey.minecraft.MinecraftScheduler;
import net.whimxiqal.odyssey.minecraft.MinecraftWorld;
import net.whimxiqal.odyssey.minecraft.PlatformApi;
import org.apache.logging.log4j.Logger;
import org.spongepowered.api.ResourceKey;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.block.BlockState;
import org.spongepowered.api.entity.Entity;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.world.chunk.ChunkEvent;
import org.spongepowered.api.world.chunk.WorldChunk;
import org.spongepowered.api.world.server.ServerWorld;
import org.spongepowered.api.world.volume.stream.StreamOptions;
import org.spongepowered.math.vector.Vector3i;
import org.spongepowered.plugin.PluginContainer;

/**
 * The Sponge {@link PlatformApi}: resolves worlds by key and snapshots one chunk column's block
 * states into a plain array (Sponge has no {@code ChunkSnapshot}), taken on the main server thread
 * and then read freely from search worker threads.
 *
 * <p><b>Load policy.</b> An already-loaded chunk is copied immediately. Otherwise, unless the
 * policy is {@code LOADED_ONLY}, {@link ServerWorld#loadChunk(int, int, int, boolean) loadChunk} is
 * issued ({@code shouldGenerate} only for {@code GENERATE}); an empty result means the chunk is
 * ungenerated (so it stays unknown). A present-but-not-yet-loaded chunk is parked in {@link
 * #pending} and completed when its {@link ChunkEvent.Blocks.Load} fires (with a safety timeout so a
 * missed event never stalls a search).
 */
final class SpongePlatformApi implements PlatformApi<Entity> {

  /** How long a parked chunk fetch waits for its load event before giving up (unknown). */
  private static final long PENDING_TIMEOUT_MILLIS = 10_000L;

  private final SpongeScheduler scheduler;
  private final Logger logger;
  private final ConcurrentHashMap<ChunkKey, List<CompletableFuture<MinecraftChunk>>> pending =
      new ConcurrentHashMap<>();

  private record ChunkKey(String world, int cx, int cz) {}

  SpongePlatformApi(PluginContainer plugin, SpongeScheduler scheduler) {
    this.scheduler = scheduler;
    this.logger = plugin.logger();
    Sponge.eventManager().registerListeners(plugin, this);
  }

  @Override
  public MinecraftScheduler<Entity> scheduler() {
    return scheduler;
  }

  @Override
  public CompletableFuture<MinecraftChunk> fetchChunk(
      int chunkX, int chunkZ, MinecraftWorld world, ChunkLoadPolicy policy, boolean urgent) {
    Optional<ServerWorld> resolved =
        Sponge.server().worldManager().world(ResourceKey.resolve(world.key()));
    if (resolved.isEmpty()) {
      return CompletableFuture.completedFuture(MinecraftChunk.Unknown.INSTANCE);
    }
    ServerWorld serverWorld = resolved.get();
    CompletableFuture<MinecraftChunk> future = new CompletableFuture<>();
    // The world must be read on the main server thread; the resulting block array is a detached
    // copy that is safe to read off-thread afterwards.
    scheduler.runGlobal(() -> resolveOnMain(serverWorld, chunkX, chunkZ, policy, future));
    return future;
  }

  /** Resolves a fetch on the main thread: copy now, park for a load, or give up. */
  private void resolveOnMain(
      ServerWorld world,
      int cx,
      int cz,
      ChunkLoadPolicy policy,
      CompletableFuture<MinecraftChunk> future) {
    if (world.isChunkLoaded(cx, 0, cz, false)) {
      future.complete(copy(world, cx, cz));
      return;
    }
    if (policy == ChunkLoadPolicy.LOADED_ONLY) {
      future.complete(MinecraftChunk.Unknown.INSTANCE);
      return;
    }
    Optional<WorldChunk> loaded = world.loadChunk(cx, 0, cz, policy == ChunkLoadPolicy.GENERATE);
    if (loaded.isEmpty()) {
      future.complete(MinecraftChunk.Unknown.INSTANCE); // ungenerated (and not generating)
      return;
    }
    if (world.isChunkLoaded(cx, 0, cz, false)) {
      future.complete(copy(world, cx, cz)); // loaded synchronously
      return;
    }
    // Present but not yet loaded: park until its load event, with a re-check for a load that raced
    // in, and a timeout so a missed event never stalls the search.
    ChunkKey key = new ChunkKey(world.key().asString(), cx, cz);
    pending.computeIfAbsent(key, ignored -> new CopyOnWriteArrayList<>()).add(future);
    if (world.isChunkLoaded(cx, 0, cz, false) && removePending(key, future)) {
      future.complete(copy(world, cx, cz));
      return;
    }
    scheduler.runAsyncLater(
        () -> {
          if (removePending(key, future)) {
            future.complete(MinecraftChunk.Unknown.INSTANCE);
          }
        },
        PENDING_TIMEOUT_MILLIS);
  }

  /** Completes any fetches parked on a chunk when it finishes loading. */
  @Listener
  public void onChunkLoad(ChunkEvent.Blocks.Load event) {
    Vector3i position = event.chunkPosition();
    ChunkKey key = new ChunkKey(event.worldKey().asString(), position.x(), position.z());
    List<CompletableFuture<MinecraftChunk>> waiters = pending.remove(key);
    if (waiters == null || waiters.isEmpty()) {
      return;
    }
    ResourceKey worldKey = event.worldKey();
    int cx = position.x();
    int cz = position.z();
    scheduler.runGlobal(
        () -> {
          Optional<ServerWorld> world = Sponge.server().worldManager().world(worldKey);
          MinecraftChunk chunk =
              world.isPresent() ? copy(world.get(), cx, cz) : MinecraftChunk.Unknown.INSTANCE;
          for (CompletableFuture<MinecraftChunk> waiter : waiters) {
            waiter.complete(chunk);
          }
        });
  }

  /** Snapshots one chunk column's block states into an array (must run on the main thread). */
  private MinecraftChunk copy(ServerWorld world, int cx, int cz) {
    int baseX = cx << 4;
    int baseZ = cz << 4;
    int minY = world.min().y();
    int maxY = world.max().y();
    int height = maxY - minY + 1;
    Vector3i min = new Vector3i(baseX, minY, baseZ);
    Vector3i max = new Vector3i(baseX + 15, maxY, baseZ + 15);
    BlockState[] states = new BlockState[16 * height * 16];
    int[] copied = {0};
    try {
      // Blocks only: block entities, biomes and entities are never read by the search, and the
      // entity leg of createArchetypeVolume is broken in Sponge 12 (half-block offset, throws for
      // entities on the volume's minimum face).
      world
          .blockStateStream(min, max, StreamOptions.lazily())
          .forEach(
              (volume, state, x, y, z) -> {
                int localX = (int) Math.floor(x) - baseX;
                int localY = (int) Math.floor(y) - minY;
                int localZ = (int) Math.floor(z) - baseZ;
                if (localX < 0
                    || localX > 15
                    || localZ < 0
                    || localZ > 15
                    || localY < 0
                    || localY >= height) {
                  return; // outside the requested column; ignore rather than fail the snapshot
                }
                states[SpongeChunk.index(localX, localY, localZ)] = state;
                copied[0]++;
              });
    } catch (Exception e) {
      logger.error("Sponge could not copy chunk [{}, {}]", cx, cz, e);
      return MinecraftChunk.Unknown.INSTANCE;
    }
    if (copied[0] == 0) {
      return MinecraftChunk.Unknown.INSTANCE; // nothing streamed: treat the chunk as unavailable
    }
    return new SpongeChunk(states, minY, height);
  }

  /** Removes one parked future from a key's list; returns whether it was still parked. */
  private boolean removePending(ChunkKey key, CompletableFuture<MinecraftChunk> future) {
    List<CompletableFuture<MinecraftChunk>> waiters = pending.get(key);
    if (waiters == null) {
      return false;
    }
    boolean removed = waiters.remove(future);
    if (waiters.isEmpty()) {
      pending.remove(key, waiters);
    }
    return removed;
  }
}
