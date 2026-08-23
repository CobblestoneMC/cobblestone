/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge12.api;

import org.spongepowered.plugin.PluginContainer;

/**
 * Where a plugin registers its {@link SearchModificationService} (transitions, break-checks,
 * pass-checks) so Cobblestone's searches consult it. Cobblestone provides one registrar (obtained
 * via {@link CobblestoneCoreApi#registrar()}) and owns the resulting collection.
 *
 * <p>There is nothing to unregister on disable: everything an owner registered is dropped
 * automatically when that plugin's container stops.
 */
public interface SearchModificationRegistrar {

  /**
   * Registers a search modifier owned by a plugin.
   *
   * @param owner the registering plugin (its registrations are dropped when it stops)
   * @param service the search modifier to consult during searches
   */
  void register(PluginContainer owner, SearchModificationService service);
}
