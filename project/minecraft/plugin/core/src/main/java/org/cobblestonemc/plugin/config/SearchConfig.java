/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.config;

import org.cobblestonemc.api.SearchSettings;

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
}
