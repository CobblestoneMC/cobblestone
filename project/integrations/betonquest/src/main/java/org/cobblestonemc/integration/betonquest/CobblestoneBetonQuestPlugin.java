/*
 * CobblestoneBetonQuest — a BetonQuest integration for the Cobblestone navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. Because it links against BetonQuest (GPL-3.0), this
 * module is distributed under the GPL rather than Cobblestone's MIT license. It is distributed WITHOUT
 * ANY WARRANTY. See the GNU General Public License (the LICENSE file in this module) for details.
 */
package org.cobblestonemc.integration.betonquest;

import java.util.Optional;
import org.betonquest.betonquest.api.BetonQuestApi;
import org.betonquest.betonquest.api.BetonQuestApiService;
import org.bukkit.plugin.java.JavaPlugin;
import org.cobblestonemc.paper.plugin.api.CobblestonePaperApi;

/**
 * Entry point for the BetonQuest integration. Obtains the {@link BetonQuestApi} through
 * BetonQuest's addon service, then registers — as an ordinary Bukkit service — a destination
 * provider ({@code /navigate compass <name>}) and, as a listener, the compass hook that
 * auto-navigates a player to a quest-compass target when they set it. Cobblestone discovers the
 * destination provider automatically.
 */
public final class CobblestoneBetonQuestPlugin extends JavaPlugin {

  @Override
  public void onEnable() {
    Optional<BetonQuestApiService> service = BetonQuestApiService.get();
    var betonQuest = getServer().getPluginManager().getPlugin("BetonQuest");
    if (service.isEmpty() || betonQuest == null) {
      getLogger().severe("BetonQuest API not available; disabling CobblestoneBetonQuest.");
      getServer().getPluginManager().disablePlugin(this);
      return;
    }
    BetonQuestApi api = service.get().api(this);

    saveDefaultConfig();
    QuestNavPrefs prefs = new QuestNavPrefs(getConfig());

    CobblestonePaperApi.registrar()
        .registerDestinations(betonQuest, new BetonQuestDestinationService(api));
    getServer().getPluginManager().registerEvents(new BetonQuestCompassListener(api, prefs), this);

    getLogger().info("CobblestoneBetonQuest enabled.");
  }
}
