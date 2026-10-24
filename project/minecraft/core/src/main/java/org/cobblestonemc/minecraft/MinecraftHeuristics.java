/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft;

import org.cobblestonemc.Cell;
import org.cobblestonemc.HeuristicStrategy;
import org.cobblestonemc.Heuristics;
import org.cobblestonemc.api.SearchSettings;
import org.cobblestonemc.minecraft.api.MinecraftSearchSettings;
import org.cobblestonemc.minecraft.movement.MinecraftMovementBehavior;
import org.cobblestonemc.minecraft.regions.MinecraftAgent;
import org.cobblestonemc.minecraft.regions.MinecraftTerrain;
import org.cobblestonemc.minecraft.regions.RegionStores;
import org.cobblestonemc.minecraft.regions.WorldChunkColumns;
import org.cobblestonemc.regions.RegionHeuristic;
import org.cobblestonemc.regions.RegionStore;
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
   * Builds the heuristic the settings select, for one player.
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
   * <p>With a server's stores, terrain any earlier search built is read back rather than built
   * again. Without, as on the benchmark, each solve builds its own.
   *
   * @param behavior how the player moves
   * @param chunks where the search reads blocks from
   * @param agent the navigating player
   * @param origin where the search starts
   * @param settings the search settings, which select the heuristic and the excluded modes
   * @param stores the server's region stores, or {@code null} for each solve's own
   * @return the heuristic strategy
   */
  public static HeuristicStrategy forPlayer(
      MinecraftMovementBehavior behavior,
      ChunkSource chunks,
      CobblestonePlayer agent,
      Cell origin,
      MinecraftSearchSettings settings,
      @Nullable RegionStores stores) {
    return switch (settings.settings().heuristic()) {
      case RUNNING_AVERAGE -> Heuristics.runningAverage(behavior.cheapestCostPerBlock());
      case REGIONS ->
          new RegionHeuristic<MinecraftChunk>(
              // The domain of a Tier-3 target region is always the world that leg runs in; the
              // casts are the price of HeuristicStrategy being domain-agnostic.
              target ->
                  new WorldChunkColumns(
                      chunks, (MinecraftWorld) target.domain(), target.nearestBoundaryCell(origin)),
              target ->
                  stores == null
                      ? RegionStore.inMemory(
                          MinecraftTerrain.spec((MinecraftWorld) target.domain()))
                      : stores.of((MinecraftWorld) target.domain()),
              target ->
                  MinecraftAgent.forPlayer(
                      agent,
                      settings.excludedModes(),
                      ((MinecraftWorld) target.domain()).environment()),
              behavior.cheapestCostPerBlock(),
              search -> {});
    };
  }
}
