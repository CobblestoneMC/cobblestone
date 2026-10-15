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
import org.cobblestonemc.minecraft.lod.RegionCost;
import org.cobblestonemc.minecraft.lod.RegionGraph;
import org.cobblestonemc.minecraft.lod.RegionHeuristic;
import org.cobblestonemc.minecraft.lod.RegionStores;
import org.cobblestonemc.minecraft.lod.WorldChunkColumns;
import org.cobblestonemc.minecraft.modes.MinecraftModes;
import org.jetbrains.annotations.Nullable;

/**
 * Builds the estimate a player's search prices its remaining journey with.
 *
 * <p>Shared by every platform so that the configured {@link SearchSettings.Heuristic} means the
 * same thing on each of them: a platform that built its own would be one more place for the setting
 * to be read and then quietly ignored.
 */
public final class MinecraftHeuristics {

  private MinecraftHeuristics() {}

  /**
   * Builds the heuristic the settings select, for one player.
   *
   * <p>Per-player, not a shared constant: the bound has to reflect what this player can actually
   * do, or Tier 1 prices every route as if they could fly. See {@link
   * MinecraftModes#cheapestCostPerBlock}.
   *
   * <p>The region heuristic reads terrain, so its column source is built per solve from the target
   * region's own world -- a trip through a portal has legs in different worlds, and a source bound
   * to the origin's world would answer every later leg from the wrong terrain without ever failing.
   *
   * <p>Nothing is read up front. The fine search parks on an estimate whose chunks have not
   * arrived, the same way it parks on a mode waiting for blocks, so the terrain that gets
   * classified is the terrain the search actually asks about.
   *
   * @param agent the navigating player
   * @param origin where the search starts
   * @param settings the search settings, which select the heuristic and the excluded modes
   * @return the heuristic strategy
   */
  public static HeuristicStrategy forPlayer(
      CobblestonePlayer agent, Cell origin, MinecraftSearchSettings settings) {
    return forPlayer(agent, origin, settings, null);
  }

  /**
   * Builds the heuristic the settings select, for one player, reading built terrain from a server's
   * shared stores.
   *
   * <p>With stores, terrain any earlier search built — before the last restart too, if they keep it
   * on disk — is read back rather than built again. Without, each solve builds its own.
   *
   * @param agent the navigating player
   * @param origin where the search starts
   * @param settings the search settings, which select the heuristic and the excluded modes
   * @param stores the server's region stores, or {@code null} for each solve's own
   * @return the heuristic strategy
   */
  public static HeuristicStrategy forPlayer(
      CobblestonePlayer agent,
      Cell origin,
      MinecraftSearchSettings settings,
      @Nullable RegionStores stores) {
    if (settings.settings().heuristic() == SearchSettings.Heuristic.RUNNING_AVERAGE) {
      return Heuristics.runningAverage(
          MinecraftModes.cheapestCostPerBlock(agent, settings.excludedModes()));
    }
    return new RegionHeuristic(
        target ->
            // The domain of a Tier-3 target region is always the world that leg runs in; the cast
            // is the price of HeuristicStrategy being domain-agnostic.
            new WorldChunkColumns(
                (MinecraftWorld) target.domain(), target.nearestBoundaryCell(origin)),
        stores == null ? null : target -> stores.of((MinecraftWorld) target.domain()),
        RegionCost.forPlayer(agent, settings.excludedModes()),
        RegionGraph.DEFAULT_DETOUR_RATIO,
        MinecraftModes.cheapestCostPerBlock(agent, settings.excludedModes()),
        false);
  }
}
