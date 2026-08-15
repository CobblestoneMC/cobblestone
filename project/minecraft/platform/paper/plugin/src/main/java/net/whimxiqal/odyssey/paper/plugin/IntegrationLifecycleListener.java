/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper.plugin;

import net.whimxiqal.odyssey.paper.PaperNavigationServiceImpl;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;

/**
 * Drops everything a plugin registered into Odyssey's registries when that plugin disables — the
 * lifecycle bookkeeping the Bukkit service manager used to do for us, now that Odyssey owns the
 * collections. Firing for Odyssey's own disable is a harmless no-op mid-shutdown.
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
