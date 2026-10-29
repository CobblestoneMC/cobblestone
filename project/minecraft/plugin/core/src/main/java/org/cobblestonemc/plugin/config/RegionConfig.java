/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.config;

import org.cobblestonemc.Workers;
import org.cobblestonemc.minecraft.regions.RegionStores;

/** The region layer's settings, read the same way on every platform. */
public final class RegionConfig {

  private RegionConfig() {}

  /**
   * Creates the server's region stores from the settings.
   *
   * @param config the loaded configuration
   * @param keys its keys
   * @param workers the threads the stores build on
   * @return the stores
   */
  public static RegionStores stores(ConfigManager config, ConfigKeys keys, Workers workers) {
    return new RegionStores(
        Math.max(16, config.get(keys.regionsMemory)) * 1024L * 1024L,
        workers.compute(),
        workers.time());
  }
}
