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
import org.cobblestonemc.minecraft.modes.MinecraftModes;

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
   * do, or Tier 1 prices every route as if they could fly. See {@link
   * MinecraftModes#cheapestCostPerBlock}.
   *
   * @param agent the navigating player
   * @param origin where the search starts
   * @param settings the search settings, which select the heuristic and the excluded modes
   * @return the heuristic strategy
   */
  public static HeuristicStrategy forPlayer(
      CobblestonePlayer agent, Cell origin, MinecraftSearchSettings settings) {
    return switch (settings.settings().heuristic()) {
      case RUNNING_AVERAGE ->
          Heuristics.runningAverage(
              MinecraftModes.cheapestCostPerBlock(agent, settings.excludedModes()));
    };
  }
}
