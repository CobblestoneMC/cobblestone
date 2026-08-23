/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper.plugin;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.cobblestonemc.paper.PaperNavigationServiceImpl;

/**
 * Drops everything a plugin registered into Cobblestone's registries when that plugin disables —
 * the lifecycle bookkeeping the Bukkit service manager used to do for us, now that Cobblestone owns
 * the collections. Firing for Cobblestone's own disable is a harmless no-op mid-shutdown.
 */
final class IntegrationLifecycleListener implements Listener {

  private final PaperNavigationServiceImpl searchModifiers;
  private final PaperIntegrationRegistry integrations;

  IntegrationLifecycleListener(
      PaperNavigationServiceImpl searchModifiers, PaperIntegrationRegistry integrations) {
    this.searchModifiers = searchModifiers;
    this.integrations = integrations;
  }

  @EventHandler
  void onPluginDisable(PluginDisableEvent event) {
    String owner = event.getPlugin().getName();
    searchModifiers.purgeOwner(owner);
    integrations.purge(owner);
  }
}
