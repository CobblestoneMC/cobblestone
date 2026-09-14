/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.integration.citizens;

import org.bukkit.plugin.java.JavaPlugin;
import org.cobblestonemc.paper.plugin.api.CobblestonePaperApi;

/**
 * Entry point for the Citizens integration. Registers — as an ordinary Bukkit service — a
 * destination provider that surfaces the server's NPCs as {@code /navigate citizens npc <name>}
 * targets. Cobblestone discovers it automatically.
 */
public final class CobblestoneCitizensPlugin extends JavaPlugin {

  @Override
  public void onEnable() {
    var citizens = getServer().getPluginManager().getPlugin("Citizens");
    if (citizens == null) {
      getLogger().severe("Citizens not found; disabling CobblestoneCitizens.");
      getServer().getPluginManager().disablePlugin(this);
      return;
    }

    CobblestonePaperApi.registrar()
        .registerDestinations(citizens, new CitizensDestinationService());

    getLogger().info("CobblestoneCitizens enabled.");
  }
}
