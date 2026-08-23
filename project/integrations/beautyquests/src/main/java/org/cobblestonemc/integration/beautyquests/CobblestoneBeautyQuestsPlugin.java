/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.integration.beautyquests;

import org.bukkit.plugin.java.JavaPlugin;
import org.cobblestonemc.paper.plugin.api.CobblestonePaperApi;

/**
 * Entry point for the BeautyQuests integration. Registers — as an ordinary Bukkit service — a
 * destination provider ({@code /navigate quest <name>}) and, as a listener, the stage hook that
 * auto-navigates a player to a quest stage when they reach it. Cobblestone discovers the
 * destination provider automatically. Targets the released BeautyQuests API (the {@code
 * PlayerAccount} model).
 */
public final class CobblestoneBeautyQuestsPlugin extends JavaPlugin {

  @Override
  public void onEnable() {
    if (getServer().getPluginManager().getPlugin("BeautyQuests") == null) {
      getLogger().severe("BeautyQuests not found; disabling CobblestoneBeautyQuests.");
      getServer().getPluginManager().disablePlugin(this);
      return;
    }

    saveDefaultConfig();
    QuestNavPrefs prefs = new QuestNavPrefs(getConfig());

    CobblestonePaperApi.registrar()
        .registerDestinations(this, new BeautyQuestsDestinationService());
    getServer().getPluginManager().registerEvents(new BeautyQuestsStageListener(prefs), this);

    getLogger().info("CobblestoneBeautyQuests enabled.");
  }
}
