/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.config;

import java.util.Set;
import org.cobblestonemc.api.SearchSettings;
import org.cobblestonemc.minecraft.api.CostWeights;
import org.cobblestonemc.minecraft.api.MinecraftSearchSettings;

/** The search settings, read the same way on every platform. */
public final class SearchConfig {

  private SearchConfig() {}

  /**
   * Reads the search settings as they stand now. They are live, so read them for each search.
   *
   * @param config the loaded configuration
   * @param keys its keys
   * @return the settings
   */
  public static SearchSettings read(ConfigManager config, ConfigKeys keys) {
    SearchSettings.Heuristic heuristic = config.get(keys.algorithmHeuristic);
    return SearchSettings.builder()
        .maxCellsVisited(config.get(keys.algorithmMaxCellsVisited))
        .maxWallClockMillis(config.get(keys.algorithmMaxWallClockSeconds) * 1000L)
        .tier1UnsolvedPessimism(config.get(keys.algorithmTier1UnsolvedPessimism))
        .runningAverageWidth(config.get(keys.algorithmRunningAverageWidth))
        .heuristic(heuristic)
        .heuristicWeight(config.get(keys.heuristicWeight(heuristic)))
        .build();
  }

  /**
   * Reads the search settings and what hazards cost, as they stand now, with nothing excluded.
   *
   * @param config the loaded configuration
   * @param keys its keys
   * @return the settings
   */
  public static MinecraftSearchSettings readMinecraft(ConfigManager config, ConfigKeys keys) {
    return new MinecraftSearchSettings(
        read(config, keys), Set.of(), Set.of(), Set.of(), costs(config, keys));
  }

  /**
   * Reads what hazards cost. A negative weight, which would make a hazard cheaper than its time, is
   * read as 0.
   *
   * @param config the loaded configuration
   * @param keys its keys
   * @return the weights
   */
  public static CostWeights costs(ConfigManager config, ConfigKeys keys) {
    return new CostWeights(
        Math.max(0, config.get(keys.costsDamagePerHalfHeart)),
        Math.max(0, config.get(keys.costsEnderPearl)),
        Math.max(0, config.get(keys.costsDangerousTerrain)),
        Math.max(0, config.get(keys.costsDangerousTerrainRadius)),
        Math.max(0, config.get(keys.costsBuiltBlock)),
        Math.max(0, config.get(keys.costsCrampedGliding)));
  }
}
