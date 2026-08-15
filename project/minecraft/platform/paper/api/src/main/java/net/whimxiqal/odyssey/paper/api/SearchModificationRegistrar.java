/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper.api;

import org.bukkit.plugin.Plugin;

/**
 * Where a plugin registers its {@link SearchModificationService} (transitions, break-checks,
 * pass-checks) so Odyssey's searches consult it. Odyssey provides one registrar (obtained via
 * {@link OdysseyCoreAPI#registrar()}) and owns the resulting collection — this replaces registering
 * a service per provider into Bukkit for Odyssey to enumerate.
 *
 * <p>There is nothing to unregister on disable: everything an owner registered is dropped
 * automatically when that plugin disables.
 */
public interface SearchModificationRegistrar {

  /**
   * Registers a search modifier owned by a plugin.
   *
   * @param owner the registering plugin (its registrations are dropped when it disables)
   * @param service the search modifier to consult during searches
   */
  void register(Plugin owner, SearchModificationService service);
}
