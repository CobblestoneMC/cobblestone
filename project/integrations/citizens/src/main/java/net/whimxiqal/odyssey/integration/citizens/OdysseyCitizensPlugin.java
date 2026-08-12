/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.integration.citizens;

import net.whimxiqal.odyssey.paper.plugin.api.Odyssey;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Entry point for the Citizens integration. Registers — as an ordinary Bukkit service — a
 * destination provider that surfaces the server's NPCs as {@code /navigate citizens npc <name>}
 * targets. Odyssey discovers it automatically.
 */
public final class OdysseyCitizensPlugin extends JavaPlugin {

  @Override
  public void onEnable() {
    if (getServer().getPluginManager().getPlugin("Citizens") == null) {
      getLogger().severe("Citizens not found; disabling OdysseyCitizens.");
      getServer().getPluginManager().disablePlugin(this);
      return;
    }

    Odyssey.register(this, new CitizensDestinationService());

    getLogger().info("OdysseyCitizens enabled.");
  }
}
