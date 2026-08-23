/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.integration.towny;

import org.bukkit.plugin.java.JavaPlugin;
import org.cobblestonemc.paper.api.CobblestoneCoreApi;
import org.cobblestonemc.paper.plugin.api.CobblestonePaperApi;

/**
 * Entry point for the Towny integration. Registers a destination provider (navigate to towns,
 * plots, outposts) and a search modifier (route through spawns; respect build protection when
 * mining) as ordinary Bukkit services, which Cobblestone discovers automatically.
 */
public final class CobblestoneTownyPlugin extends JavaPlugin {

  @Override
  public void onEnable() {
    if (getServer().getPluginManager().getPlugin("Towny") == null) {
      getLogger().severe("Towny not found; disabling CobblestoneTowny.");
      getServer().getPluginManager().disablePlugin(this);
      return;
    }
    CobblestonePaperApi.registrar().registerDestinations(this, new TownyDestinationService());
    CobblestoneCoreApi.registrar().register(this, new TownySearchModificationService(this));
    getLogger().info("CobblestoneTowny enabled.");
  }
}
