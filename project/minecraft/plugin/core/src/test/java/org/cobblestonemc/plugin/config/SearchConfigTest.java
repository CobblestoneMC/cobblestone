/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.cobblestonemc.CobblestoneLogger;
import org.cobblestonemc.api.SearchSettings;
import org.cobblestonemc.minecraft.ChunkLoadPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Each heuristic runs at its own weight setting, so tuning one never carries over to the other. */
class SearchConfigTest {

  @TempDir Path directory;

  private final List<String> warnings = new ArrayList<>();

  private SearchSettings read(String yaml) throws IOException {
    Path file = directory.resolve("config.yml");
    if (yaml != null) {
      Files.writeString(file, yaml);
    }
    ConfigManager config =
        new ConfigManager(
            file,
            new CobblestoneLogger() {
              @Override
              public void trace(String message, Object... args) {}

              @Override
              public void debug(String message, Object... args) {}

              @Override
              public void info(String message, Object... args) {}

              @Override
              public void warn(String message, Object... args) {
                warnings.add(message);
              }

              @Override
              public void error(String message, Throwable throwable, Object... args) {}
            });
    ConfigKeys keys =
        new ConfigKeys(
            config,
            new ConfigPlatform(
                "Test", ChunkLoadPolicy.ALLOW_LOAD, List.of(ChunkLoadPolicy.ALLOW_LOAD), ""));
    config.load();
    return SearchConfig.read(config, keys);
  }

  @Test
  void regionsRunsAtItsOwnWeightByDefault() throws IOException {
    SearchSettings settings = read(null);
    assertEquals(SearchSettings.Heuristic.REGIONS, settings.heuristic());
    assertEquals(1.7, settings.heuristicWeight());
  }

  @Test
  void aWeightTunedForTheRunningAverageStaysWithIt() throws IOException {
    String tuned = "search:\n  algorithm:\n    heuristic_weight: 2.5\n";
    assertEquals(1.7, read(tuned).heuristicWeight(), "not carried over to REGIONS");

    SearchSettings average = read(tuned + "    heuristic: running_average\n");
    assertEquals(SearchSettings.Heuristic.RUNNING_AVERAGE, average.heuristic());
    assertEquals(2.5, average.heuristicWeight());
  }

  @Test
  void aMisspelledHeuristicFallsBackToTheDefaultWithAWarning() throws IOException {
    SearchSettings settings = read("search:\n  algorithm:\n    heuristic: regoins\n");
    assertEquals(SearchSettings.Heuristic.REGIONS, settings.heuristic());
    assertFalse(warnings.isEmpty());
  }
}
