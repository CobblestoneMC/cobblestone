/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.config;

import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Consumer;
import org.cobblestonemc.api.SearchSettings;
import org.cobblestonemc.minecraft.regions.RegionStores;
import org.jetbrains.annotations.Nullable;

/**
 * The region layer's settings, read the same way on every platform: which heuristic searches use,
 * and the stores the {@code REGIONS} heuristic keeps.
 */
public final class RegionConfig {

  private final ConfigManager config;
  private final ConfigKeys keys;
  private final Consumer<String> warn;

  /** The heuristic setting last read, so a bad one is warned about once. */
  private volatile @Nullable String lastHeuristic;

  /**
   * Creates the reader.
   *
   * @param config the loaded configuration
   * @param keys its keys
   * @param warn where to warn an operator about a setting that cannot be used
   */
  public RegionConfig(ConfigManager config, ConfigKeys keys, Consumer<String> warn) {
    this.config = config;
    this.keys = keys;
    this.warn = warn;
  }

  /**
   * Creates the server's region stores from the settings.
   *
   * @param dataFolder the plugin's data folder, under which regions are kept if they persist
   * @return the stores
   */
  public RegionStores stores(Path dataFolder) {
    return new RegionStores(
        config.get(keys.regionsPersist) ? dataFolder.resolve("regions") : null,
        Math.max(16, config.get(keys.regionsMemoryMegabytes)) * 1024L * 1024L,
        Math.max(1, Runtime.getRuntime().availableProcessors() / 4));
  }

  /**
   * Returns how often new regions are written to disk, in seconds.
   *
   * @return the interval
   */
  public long flushSeconds() {
    return Math.max(5, config.get(keys.regionsFlushSeconds));
  }

  /**
   * Reads the configured heuristic, falling back to the default on anything unrecognised.
   *
   * <p>A misspelled value should not stop a server from starting, and it should not quietly pick
   * the other heuristic either: the two behave differently enough that an operator would be
   * debugging the wrong thing. So it warns, once per value, and uses the default.
   *
   * @return the heuristic
   */
  public SearchSettings.Heuristic heuristic() {
    String configured = config.get(keys.algorithmHeuristic);
    // Read for every search, so the warning below is said once per value rather than per search.
    String previous = lastHeuristic;
    lastHeuristic = configured;
    try {
      return SearchSettings.Heuristic.valueOf(configured.strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException unknown) {
      if (!configured.equals(previous)) {
        warn.accept(
            "search.algorithm.heuristic is '"
                + configured
                + "', which is not a heuristic; using "
                + SearchSettings.DEFAULT_HEURISTIC
                + ". Valid values: RUNNING_AVERAGE, REGIONS");
      }
      return SearchSettings.DEFAULT_HEURISTIC;
    }
  }
}
