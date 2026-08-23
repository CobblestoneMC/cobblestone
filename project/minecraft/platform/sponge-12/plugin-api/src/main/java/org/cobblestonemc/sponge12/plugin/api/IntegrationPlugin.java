/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge12.plugin.api;

import org.spongepowered.plugin.PluginContainer;

/** A plugin that integrates with Cobblestone, identified by its {@link PluginContainer}. */
public interface IntegrationPlugin {

  PluginContainer target();
}
