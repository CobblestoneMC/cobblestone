/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.config;

import org.cobblestonemc.CobblestoneLogger;
import org.cobblestonemc.Workers;

/** The thread settings, read the same way on every platform. */
public final class ThreadsConfig {

  private ThreadsConfig() {}

  /**
   * Starts the threads Cobblestone runs on besides the server's own, sized by the settings.
   *
   * @param config the loaded configuration
   * @param keys its keys
   * @param logger where work dropped after closing is reported
   * @return the threads, to close last on disable
   */
  public static Workers workers(ConfigManager config, ConfigKeys keys, CobblestoneLogger logger) {
    return new Workers(logger, config.get(keys.threadsCompute), config.get(keys.threadsIo));
  }
}
