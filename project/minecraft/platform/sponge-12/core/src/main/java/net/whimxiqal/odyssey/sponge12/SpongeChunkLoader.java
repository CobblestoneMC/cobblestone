/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.sponge12;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntSupplier;
import net.whimxiqal.odyssey.OdysseyLogger;
import net.whimxiqal.odyssey.minecraft.ChunkLoadPolicy;
import net.whimxiqal.odyssey.minecraft.MinecraftChunk;
import org.spongepowered.api.ResourceKey;
import org.spongepowered.api.block.BlockState;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.world.chunk.ChunkEvent;
import org.spongepowered.api.util.Ticks;
import org.spongepowered.api.world.chunk.BlockChunk;
import org.spongepowered.api.world.chunk.WorldChunk;
import org.spongepowered.api.world.server.ServerWorld;
import org.spongepowered.api.world.server.Ticket;
import org.spongepowered.api.world.server.TicketType;
import org.spongepowered.api.world.volume.block.BlockVolume;
import org.spongepowered.math.vector.Vector3i;

/**
 * Obtains chunk snapshots on Sponge, which has no asynchronous chunk API and cannot read a chunk
 * that is not loaded.
 *
 * <p><b>Why tickets.</b> {@code ServerWorld#loadChunk} resolves to {@code getChunk} at {@code
 * ChunkStatus.EMPTY}: for an unloaded chunk that yields a proto chunk, which Sponge reports as
 * absent, and no chunk is ever promoted or loaded. The only route to a readable chunk is a chunk
 * loading ticket, which drives the chunk to full status — at which point Sponge fires {@link
 * ChunkEvent.Blocks.Load} and we copy the blocks out of the event's own volume.
 *
 * <p><b>Held briefly.</b> A chunk is needed only long enough to copy it, so the ticket is released
 * as soon as the copy is made. What is bounded is therefore how much chunk loading may be in flight
 * at once, not how much ground a search may cover. Note that a ticket also pulls in the ring of
 * chunks around its target: Sponge's smallest permitted radius is 1, which maps to a vanilla ticket
 * level of 32, so the target chunk is loaded and block-ticking and its eight neighbours are loaded
 * at border level.
 *
 * <p><b>Generation.</b> Because a ticket generates terrain that does not exist, {@code allow_load}
 * consults the {@link ChunkExistenceIndex} first and declines to ticket anything that is not
 * already on disk. Only {@code allow_load_and_generate} skips that check.
 */
final class SpongeChunkLoader {

  /** How long a parked fetch waits for its load event before giving up (unknown). */
  private static final long PENDING_TIMEOUT_MILLIS = 10_000L;

  /**
   * The ticket radius. Sponge rejects anything below 1, so this is the smallest region we can ask
   * for; it maps to vanilla ticket level 32.
   */
  private static final int TICKET_RADIUS = 1;

  /**
   * Ticket lifetime. Tickets are released explicitly once their chunk is copied; this only bounds
   * one whose chunk never loads, so that a lost event cannot leak a loaded chunk forever.
   */
  private static final long TICKET_LIFETIME_TICKS = 200L;

  /** Multiple of the budget beyond which even urgent requests are refused rather than queued. */
  private static final int QUEUE_LIMIT_FACTOR = 4;

  private final SpongeScheduler scheduler;
  private final OdysseyLogger logger;
  private final IntSupplier maxLoadRequests;
  private final ChunkExistenceIndex index;
  private final Map<ChunkKey, Parked> pending = new ConcurrentHashMap<>();

  /** Urgent requests waiting for a ticket slot. Server thread only. */
  private final Deque<Runnable> queued = new ArrayDeque<>();

  /** Tickets currently held. Server thread only. */
  private int outstanding;

  /** Built on first use: the builder needs a constructed game. Server thread only. */
  private TicketType<Vector3i> ticketType;

  SpongeChunkLoader(SpongeScheduler scheduler, OdysseyLogger logger, IntSupplier maxLoadRequests) {
    this.scheduler = scheduler;
    this.logger = logger;
    this.maxLoadRequests = maxLoadRequests;
    this.index = new ChunkExistenceIndex(scheduler, logger);
  }

  /**
   * Fetches one chunk snapshot.
   *
   * @param world the world
   * @param chunkX the chunk x coordinate
   * @param chunkZ the chunk z coordinate
   * @param policy how far we may go to obtain it
   * @param urgent whether a search is blocked on this chunk (as opposed to reading ahead)
   * @return the snapshot, or {@link MinecraftChunk.Unknown} if it cannot be had under the policy
   */
  CompletableFuture<MinecraftChunk> fetch(
      ServerWorld world, int chunkX, int chunkZ, ChunkLoadPolicy policy, boolean urgent) {
    CompletableFuture<MinecraftChunk> future = new CompletableFuture<>();
    // The world may only be touched on the server thread; the block array we come away with is a
    // detached copy that search workers can read freely.
    scheduler.runGlobal(() -> resolve(world, chunkX, chunkZ, policy, urgent, future));
    return future;
  }

  /** Resolves a fetch on the server thread: copy now, ticket and park, queue, or give up. */
  private void resolve(
      ServerWorld world,
      int cx,
      int cz,
      ChunkLoadPolicy policy,
      boolean urgent,
      CompletableFuture<MinecraftChunk> future) {
    if (world.isChunkLoaded(cx, 0, cz, false)) {
      future.complete(copy(world, cx, cz));
      return;
    }
    if (policy == ChunkLoadPolicy.LOADED_ONLY) {
      future.complete(MinecraftChunk.Unknown.INSTANCE);
      return;
    }
    if (policy == ChunkLoadPolicy.ALLOW_LOAD
        && index.presence(world, cx, cz) != ChunkExistenceIndex.Presence.PRESENT) {
      // Either the chunk has never been generated, or the world's scan has not finished. Both mean
      // "loading this would generate terrain, as far as we can tell", which this policy forbids.
      future.complete(MinecraftChunk.Unknown.INSTANCE);
      return;
    }

    ChunkKey key = new ChunkKey(world.key(), cx, cz);
    Parked already = pending.get(key);
    if (already != null) {
      already.waiters.add(future); // someone already holds a ticket for this chunk; ride along
      return;
    }
    if (!acquireSlot(urgent)) {
      if (!urgent) {
        future.complete(MinecraftChunk.Unknown.INSTANCE); // read-ahead yields the budget
        return;
      }
      if (queued.size() >= QUEUE_LIMIT_FACTOR * budget()) {
        logger.debug("Chunk load queue is full; treating [{}, {}] as unknown", cx, cz);
        future.complete(MinecraftChunk.Unknown.INSTANCE);
        return;
      }
      queued.add(() -> resolve(world, cx, cz, policy, true, future));
      return;
    }

    Optional<Ticket<Vector3i>> ticket = requestTicket(world, cx, cz);
    if (ticket.isEmpty()) {
      releaseSlot();
      future.complete(MinecraftChunk.Unknown.INSTANCE);
      return;
    }
    Parked parked = new Parked(world, ticket.get());
    parked.waiters.add(future);
    pending.put(key, parked);

    // The ticket may have promoted the chunk synchronously, in which case its load event has
    // already been and gone.
    if (world.isChunkLoaded(cx, 0, cz, false)) {
      deliver(key, copy(world, cx, cz));
      return;
    }
    scheduler.runAsyncLater(
        () -> {
          if (pending.get(key) == parked) {
            logger.debug("Timed out waiting for chunk [{}, {}] to load", cx, cz);
            deliver(key, MinecraftChunk.Unknown.INSTANCE);
          }
        },
        PENDING_TIMEOUT_MILLIS);
  }

  /**
   * Completes the fetches parked on a chunk when it finishes loading, copying straight out of the
   * event's own {@link BlockChunk} volume.
   *
   * <p>The volume is the chunk's block storage, handed to us by the event, so reading it needs no
   * hop back to the server thread — which matters: this event may itself fire off-thread, and
   * bouncing through the scheduler would cost a whole extra tick per chunk plus a second full copy
   * on the server thread. We must not touch the {@link ServerWorld} from here (the event contract
   * forbids it), and we do not.
   */
  @Listener
  public void onChunkLoad(ChunkEvent.Blocks.Load event) {
    logger.info(
        "Loaded chunk at {},{}, outstanding requests {}",
        event.chunkPosition().x(),
        event.chunkPosition().z(),
        outstanding);
    Vector3i position = event.chunkPosition();
    ChunkKey key = new ChunkKey(event.worldKey(), position.x(), position.z());
    if (!pending.containsKey(key)) {
      return; // loaded for someone else's reasons
    }
    deliver(key, copy(event.chunk(), position.x(), position.z()));
  }

  /**
   * Records terrain that has just come into existence, so a later {@code allow_load} search does
   * not mistake it for something that would still have to be generated.
   */
  @Listener
  public void onChunkGenerated(ChunkEvent.Generated event) {
    Vector3i position = event.chunkPosition();
    index.markPresent(event.worldKey(), position.x(), position.z());
  }

  /** Hands a result to everyone parked on a chunk and lets go of its ticket. */
  private void deliver(ChunkKey key, MinecraftChunk chunk) {
    Parked parked = pending.remove(key);
    if (parked == null) {
      return;
    }
    for (CompletableFuture<MinecraftChunk> waiter : parked.waiters) {
      waiter.complete(chunk);
    }
    if (chunk != MinecraftChunk.Unknown.INSTANCE) {
      // We just read it, so it exists — worth knowing even if the scan has not reached it yet.
      index.markPresent(key.world(), key.cx(), key.cz());
    }
    // Ticket release touches the world's distance manager: server thread only.
    scheduler.runGlobal(
        () -> {
          parked.world.chunkManager().releaseTicket(parked.ticket);
          releaseSlot();
        });
  }

  private Optional<Ticket<Vector3i>> requestTicket(ServerWorld world, int cx, int cz) {
    Vector3i position = new Vector3i(cx, 0, cz);
    try {
      return world.chunkManager().requestTicket(ticketType(), position, position, TICKET_RADIUS);
    } catch (RuntimeException e) {
      logger.error("Sponge refused a chunk loading ticket for [{}, {}]", e, cx, cz);
      return Optional.empty();
    }
  }

  private TicketType<Vector3i> ticketType() {
    if (ticketType == null) {
      ticketType =
          TicketType.<Vector3i>builder()
              .name("odyssey_search")
              .lifetime(Ticks.of(TICKET_LIFETIME_TICKS))
              .build();
    }
    return ticketType;
  }

  private int budget() {
    return Math.max(1, maxLoadRequests.getAsInt());
  }

  /**
   * Takes a ticket slot if the budget allows. Read-ahead may only use part of the budget, so a
   * burst of speculative fetches cannot starve the chunk a search is actually waiting on.
   */
  private boolean acquireSlot(boolean urgent) {
    int budget = budget();
    int limit = urgent ? budget : Math.max(1, budget - Math.max(1, budget / 4));
    if (outstanding >= limit) {
      return false;
    }
    outstanding++;
    return true;
  }

  /** Gives a slot back and lets any queued urgent requests through. Server thread only. */
  private void releaseSlot() {
    if (outstanding > 0) {
      outstanding--;
    }
    while (!queued.isEmpty() && outstanding < budget()) {
      queued.poll().run();
    }
  }

  /**
   * Snapshots one loaded chunk's block states into an array (server thread only).
   *
   * @return the snapshot, or unknown if the chunk turned out not to be loaded after all
   */
  private MinecraftChunk copy(ServerWorld world, int cx, int cz) {
    WorldChunk chunk = world.chunk(cx, 0, cz);
    if (chunk.isEmpty()) {
      return MinecraftChunk.Unknown.INSTANCE; // not actually loaded: treat the chunk as unavailable
    }
    return copy(chunk, cx, cz);
  }

  /**
   * Snapshots one chunk-shaped {@link BlockVolume} into an array. Safe on any thread as long as the
   * volume itself is (the server thread for a live {@link WorldChunk}; any thread for the volume
   * handed to us by {@link ChunkEvent.Blocks.Load}).
   *
   * <p>Read with a plain triple loop over {@link BlockVolume#block(int, int, int)} rather than
   * {@code blockStateStream}: Sponge's volume-stream machinery materializes every position into a
   * {@code LinkedHashSet} of keys, looks each block up twice, and allocates a {@code Vector3d},
   * weak reference and {@code VolumeElement} per block — a quarter of a server tick for one
   * full-height column. Block entities, biomes and entities are never read by the search.
   */
  private MinecraftChunk copy(BlockVolume volume, int cx, int cz) {
    int baseX = cx << 4;
    int baseZ = cz << 4;
    int minY = volume.min().y();
    int height = volume.max().y() - minY + 1;
    BlockState[] states = new BlockState[16 * height * 16];
    try {
      // y-major, then z, then x: matches SpongeChunk.index, so writes run straight down the array.
      for (int localY = 0; localY < height; localY++) {
        int y = minY + localY;
        for (int localZ = 0; localZ < 16; localZ++) {
          for (int localX = 0; localX < 16; localX++) {
            states[SpongeChunk.index(localX, localY, localZ)] =
                volume.block(baseX + localX, y, baseZ + localZ);
          }
        }
      }
    } catch (Exception e) {
      logger.error("Sponge could not copy chunk [{}, {}]", e, cx, cz);
      return MinecraftChunk.Unknown.INSTANCE;
    }
    return new SpongeChunk(states, minY, height);
  }

  private record ChunkKey(ResourceKey world, int cx, int cz) {}

  /** One chunk being waited on, and the ticket holding it open. */
  private static final class Parked {

    private final ServerWorld world;
    private final Ticket<Vector3i> ticket;
    private final List<CompletableFuture<MinecraftChunk>> waiters = new CopyOnWriteArrayList<>();

    private Parked(ServerWorld world, Ticket<Vector3i> ticket) {
      this.world = world;
      this.ticket = ticket;
    }
  }
}
