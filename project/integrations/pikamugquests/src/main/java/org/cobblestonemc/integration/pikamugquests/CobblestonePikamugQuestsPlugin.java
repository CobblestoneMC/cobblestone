/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.integration.pikamugquests;

import me.pikamug.quests.Quests;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.cobblestonemc.paper.plugin.api.CobblestonePaperApi;

/**
 * Entry point for the PikaMug Quests integration. Resolves the Quests API handle, then registers —
 * as an ordinary Bukkit service — a destination provider ({@code /navigate pikamugquests quest
 * <name>}) and, as a listener, the compass hook that auto-navigates a player to a quest objective
 * when its target updates. Cobblestone discovers the destination provider automatically.
 */
public final class CobblestonePikamugQuestsPlugin extends JavaPlugin {

  @Override
  public void onEnable() {
    Plugin questsPlugin = getServer().getPluginManager().getPlugin("Quests");
    if (!(questsPlugin instanceof Quests quests)) {
      getLogger().severe("Quests not found; disabling CobblestonePikamugQuests.");
      getServer().getPluginManager().disablePlugin(this);
      return;
    }

    saveDefaultConfig();
    QuestNavPrefs prefs = new QuestNavPrefs(getConfig());

    CobblestonePaperApi.registrar()
        .registerDestinations(this, new PikamugQuestsDestinationService(quests));
    getServer().getPluginManager().registerEvents(new PikamugQuestsCompassListener(prefs), this);

    getLogger().info("CobblestonePikamugQuests enabled.");
  }
}
