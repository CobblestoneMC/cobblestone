/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper.plugin.api;

import org.bukkit.plugin.Plugin;

/** A plugin that integrates with Cobblestone, identified by its Bukkit {@link Plugin}. */
public interface IntegrationPlugin {

  /**
   * Returns the plugin being integrated with.
   *
   * @return the target plugin
   */
  Plugin target();
}
