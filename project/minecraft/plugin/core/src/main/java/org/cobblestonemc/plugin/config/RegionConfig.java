/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.config;

import java.nio.file.Path;
import org.cobblestonemc.CobblestoneLogger;
import org.cobblestonemc.minecraft.regions.RegionStores;

/** The region layer's settings, read the same way on every platform. */
public final class RegionConfig {

  private RegionConfig() {}

  /**
   * Creates the server's region stores from the settings.
   *
   * @param config the loaded configuration
   * @param keys its keys
   * @param dataFolder Cobblestone's data folder, under which regions are kept if they persist
   * @param log where a write that fails is told
   * @return the stores
   */
  public static RegionStores stores(
      ConfigManager config, ConfigKeys keys, Path dataFolder, CobblestoneLogger log) {
    return new RegionStores(
        config.get(keys.regionsPersist) ? dataFolder.resolve("regions") : null,
        Math.max(16, config.get(keys.regionsMemory)) * 1024L * 1024L,
        Math.max(1, Runtime.getRuntime().availableProcessors() / 4),
        log);
  }

  /**
   * Returns how often new regions are written to disk, in seconds.
   *
   * @param config the loaded configuration
   * @param keys its keys
   * @return the interval
   */
  public static long flushPeriod(ConfigManager config, ConfigKeys keys) {
    return Math.max(5, config.get(keys.regionsFlushPeriod));
  }
}
