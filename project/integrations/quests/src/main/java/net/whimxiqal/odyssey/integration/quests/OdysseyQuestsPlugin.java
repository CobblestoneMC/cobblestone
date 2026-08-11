/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.integration.quests;

import me.pikamug.quests.Quests;
import net.whimxiqal.odyssey.paper.plugin.api.Odyssey;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Entry point for the Quests integration. Resolves the Quests API handle, then registers — as an
 * ordinary Bukkit service — a destination provider ({@code /navigate quest <name>}) and, as a
 * listener, the compass hook that auto-navigates a player to a quest objective when its target
 * updates. Odyssey discovers the destination provider automatically.
 */
public final class OdysseyQuestsPlugin extends JavaPlugin {

  @Override
  public void onEnable() {
    Plugin questsPlugin = getServer().getPluginManager().getPlugin("Quests");
    if (!(questsPlugin instanceof Quests quests)) {
      getLogger().severe("Quests not found; disabling OdysseyQuests.");
      getServer().getPluginManager().disablePlugin(this);
      return;
    }

    saveDefaultConfig();
    QuestNavPrefs prefs = new QuestNavPrefs(getConfig());

    Odyssey.register(this, new QuestsDestinationProvider(quests));
    getServer().getPluginManager().registerEvents(new QuestsCompassListener(prefs), this);

    getLogger().info("OdysseyQuests enabled.");
  }
}
