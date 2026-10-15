/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft;

import java.util.function.Supplier;
import org.cobblestonemc.Cell;
import org.cobblestonemc.HeuristicStrategy;
import org.cobblestonemc.Heuristics;
import org.cobblestonemc.api.SearchSettings;
import org.cobblestonemc.minecraft.api.MinecraftSearchSettings;
import org.cobblestonemc.minecraft.api.RegionSource;
import org.cobblestonemc.minecraft.lod.RegionCost;
import org.cobblestonemc.minecraft.lod.RegionGraph;
import org.cobblestonemc.minecraft.lod.RegionHeuristic;
import org.cobblestonemc.minecraft.lod.RegionStores;
import org.cobblestonemc.minecraft.lod.WorldChunkColumns;
import org.cobblestonemc.minecraft.movement.MinecraftMovementBehavior;
import org.jetbrains.annotations.Nullable;

/**
 * Builds the estimate a player's search prices its remaining journey with.
 *
 * <p>Shared by every platform and by the benchmark, so that a {@link SearchSettings.Heuristic}
 * means the same thing in each of them. A new estimate is added here once, and is then both what a
 * server can run and what the benchmark measures.
 */
public final class MinecraftHeuristics {

  private MinecraftHeuristics() {}

  /**
   * Builds the heuristic the settings select, for one player, each solve building its own terrain.
   *
   * @param behavior how the player moves
   * @param chunks where the search reads blocks from
   * @param agent the navigating player
   * @param origin where the search starts
   * @param settings the search settings, which select the heuristic and the excluded modes
   * @return the heuristic strategy
   */
  public static HeuristicStrategy forPlayer(
      MinecraftMovementBehavior behavior,
      ChunkSource chunks,
      CobblestonePlayer agent,
      Cell origin,
      MinecraftSearchSettings settings) {
    return forPlayer(behavior, chunks, agent, origin, settings, null, () -> chunks);
  }

  /**
   * Builds the heuristic the settings select, for one player, reading built terrain from a server's
   * shared stores.
   *
   * <p>Per-player, not a shared constant: the bound has to reflect what this player can actually
   * do, or Tier 1 prices every route as if they could fly.
   *
   * <p>The region heuristic reads terrain through the search's own chunk source, and builds its
   * column source per solve from the target region's own world: a trip through a portal has legs in
   * different worlds, and a source bound to the origin's world would answer every later leg from
   * the wrong terrain without ever failing. Nothing is read up front. The fine search parks on an
   * estimate whose chunks have not arrived, the same way it parks waiting for blocks, so the
   * terrain that gets classified is the terrain the search actually asks about.
   *
   * <p>With stores, terrain any earlier search built — before the last restart too, if they keep it
   * on disk — is read back rather than built again. Without, each solve builds its own. A search
   * whose settings ask for {@link RegionSource#LOADED} terrain uses no stores, and builds its own
   * from {@code loadedChunks}, which never reads a chunk from disk.
   *
   * @param behavior how the player moves
   * @param chunks where the search reads blocks from
   * @param agent the navigating player
   * @param origin where the search starts
   * @param settings the search settings, which select the heuristic and the excluded modes
   * @param stores the server's region stores, or {@code null} for each solve's own
   * @param loadedChunks makes a source of only the chunks the server has loaded, for a search that
   *     asks for {@link RegionSource#LOADED} terrain; called only then
   * @return the heuristic strategy
   */
  public static HeuristicStrategy forPlayer(
      MinecraftMovementBehavior behavior,
      ChunkSource chunks,
      CobblestonePlayer agent,
      Cell origin,
      MinecraftSearchSettings settings,
      @Nullable RegionStores stores,
      Supplier<ChunkSource> loadedChunks) {
    return switch (settings.settings().heuristic()) {
      case RUNNING_AVERAGE -> Heuristics.runningAverage(behavior.cheapestCostPerBlock());
      case REGIONS -> {
        boolean loaded = settings.regionSource() == RegionSource.LOADED;
        ChunkSource terrain = loaded ? loadedChunks.get() : chunks;
        RegionStores shared = loaded ? null : stores;
        yield new RegionHeuristic(
            target ->
                // The domain of a Tier-3 target region is always the world that leg runs in; the
                // cast is the price of HeuristicStrategy being domain-agnostic.
                new WorldChunkColumns(
                    terrain, (MinecraftWorld) target.domain(), target.nearestBoundaryCell(origin)),
            shared == null ? null : target -> shared.of((MinecraftWorld) target.domain()),
            RegionCost.forPlayer(agent, settings.excludedModes()),
            RegionGraph.DEFAULT_DETOUR_RATIO,
            behavior.cheapestCostPerBlock(),
            false);
      }
    };
  }
}
