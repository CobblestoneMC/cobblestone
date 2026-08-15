/*
 * OdysseyBetonQuest — a BetonQuest integration for the Odyssey navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. Because it links against BetonQuest (GPL-3.0), this
 * module is distributed under the GPL rather than Odyssey's MIT license. It is distributed WITHOUT
 * ANY WARRANTY. See the GNU General Public License (the LICENSE file in this module) for details.
 */
package net.whimxiqal.odyssey.integration.betonquest;

import java.util.Optional;
import net.whimxiqal.odyssey.paper.plugin.api.OdysseyPluginAPI;
import org.betonquest.betonquest.api.BetonQuestApi;
import org.betonquest.betonquest.api.BetonQuestApiService;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Entry point for the BetonQuest integration. Obtains the {@link BetonQuestApi} through
 * BetonQuest's addon service, then registers — as an ordinary Bukkit service — a destination
 * provider ({@code /navigate compass <name>}) and, as a listener, the compass hook that
 * auto-navigates a player to a quest-compass target when they set it. Odyssey discovers the
 * destination provider automatically.
 */
public final class OdysseyBetonQuestPlugin extends JavaPlugin {

  @Override
  public void onEnable() {
    Optional<BetonQuestApiService> service = BetonQuestApiService.get();
    if (service.isEmpty()) {
      getLogger().severe("BetonQuest API not available; disabling OdysseyBetonQuest.");
      getServer().getPluginManager().disablePlugin(this);
      return;
    }
    BetonQuestApi api = service.get().api(this);

    saveDefaultConfig();
    QuestNavPrefs prefs = new QuestNavPrefs(getConfig());

    OdysseyPluginAPI.registrar().registerDestinations(this, new BetonQuestDestinationService(api));
    getServer().getPluginManager().registerEvents(new BetonQuestCompassListener(api, prefs), this);

    getLogger().info("OdysseyBetonQuest enabled.");
  }
}
