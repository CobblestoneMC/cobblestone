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
import java.util.concurrent.Executor;
import org.cobblestonemc.Cell;
import org.cobblestonemc.TimeSource;
import org.cobblestonemc.minecraft.MinecraftChunk;
import org.cobblestonemc.minecraft.MinecraftWorld;
import org.cobblestonemc.regions.RegionSpec;
import org.cobblestonemc.regions.RegionStore;

/**
 * A server's {@link RegionStore}s, one per world.
 *
 * <p>The platform's one handle on the region layer: searches ask it for their world's store,
 * block-change listeners tell it what to forget, and trips tell it whose surroundings to keep.
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
 * <p>Builds run on the executor given, and so do forgotten changes once their delay has passed, so
 * a chunk that arrives on the server thread is not built there.
 */
public final class RegionStores {

  /**
   * How many sections out from a navigating player their pin reaches, along every axis: 4 holds a
   * cube of 9×9×9 sections, 144 blocks a side.
   */
  public static final int PIN_RADIUS = 4;

  /** How long after a block changes its sections are forgotten, with every change meanwhile. */
  public static final Duration INVALIDATION_DELAY = Duration.ofSeconds(10);

  private final long memoryBudget;
  private final Duration invalidationDelay;
  private final Executor builds;
  private final TimeSource time;
  private final Map<String, RegionStore<MinecraftChunk, RegionClass>> stores =
      new ConcurrentHashMap<>();

  /** Sections marked as changed and waiting to be forgotten. */
  private final Set<Changed> changed = ConcurrentHashMap.newKeySet();

  private record Changed(String world, int sectionX, int sectionY, int sectionZ) {}

  /**
   * Creates the registry.
   *
   * @param memoryBudget how many bytes of sections not pinned each world keeps in memory
   * @param builds where sections are built from chunks that arrive later: compute threads
   * @param time the clock changes wait on to be forgotten, running them on {@code builds}
   */
  public RegionStores(long memoryBudget, Executor builds, TimeSource time) {
    this(memoryBudget, builds, time, INVALIDATION_DELAY);
  }

  RegionStores(long memoryBudget, Executor builds, TimeSource time, Duration invalidationDelay) {
    this.memoryBudget = memoryBudget;
    this.invalidationDelay = invalidationDelay;
    this.builds = builds;
    this.time = time;
  }

  /**
   * Returns a world's store, creating it on first use.
   *
   * @param world the world
   * @return its store
   */
  public RegionStore<MinecraftChunk, RegionClass> of(MinecraftWorld world) {
    return stores.computeIfAbsent(
        world.key(), key -> new RegionStore<>(MinecraftTerrain.spec(world), memoryBudget, builds));
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
    time.schedule(() -> forget(section), invalidationDelay.toMillis());
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
   * Drops a world's store, once the world has unloaded.
   *
   * @param worldKey the world's key
   */
  public void worldUnloaded(String worldKey) {
    stores.remove(worldKey);
  }
}
