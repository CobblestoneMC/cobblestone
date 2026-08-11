/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.integration.towny;

import net.whimxiqal.odyssey.paper.plugin.api.Odyssey;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Entry point for the Towny integration. Registers a destination provider (navigate to towns,
 * plots, outposts) and a search modifier (route through spawns; respect build protection when
 * mining) as ordinary Bukkit services, which Odyssey discovers automatically.
 */
public final class OdysseyTownyPlugin extends JavaPlugin {

  @Override
  public void onEnable() {
    if (getServer().getPluginManager().getPlugin("Towny") == null) {
      getLogger().severe("Towny not found; disabling OdysseyTowny.");
      getServer().getPluginManager().disablePlugin(this);
      return;
    }
    Odyssey.register(this, new TownyDestinationProvider());
    Odyssey.register(this, new TownyModifier(this));
    getLogger().info("OdysseyTowny enabled.");
  }
}
