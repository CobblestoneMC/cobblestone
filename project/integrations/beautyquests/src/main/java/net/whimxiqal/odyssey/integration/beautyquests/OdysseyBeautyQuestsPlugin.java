/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.integration.beautyquests;

import net.whimxiqal.odyssey.paper.plugin.api.Odyssey;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Entry point for the BeautyQuests integration. Registers — as an ordinary Bukkit service — a
 * destination provider ({@code /navigate quest <name>}) and, as a listener, the stage hook that
 * auto-navigates a player to a quest stage when they reach it. Odyssey discovers the destination
 * provider automatically. Targets the released BeautyQuests API (the {@code PlayerAccount} model).
 */
public final class OdysseyBeautyQuestsPlugin extends JavaPlugin {

  @Override
  public void onEnable() {
    if (getServer().getPluginManager().getPlugin("BeautyQuests") == null) {
      getLogger().severe("BeautyQuests not found; disabling OdysseyBeautyQuests.");
      getServer().getPluginManager().disablePlugin(this);
      return;
    }

    saveDefaultConfig();
    QuestNavPrefs prefs = new QuestNavPrefs(getConfig());

    Odyssey.register(this, new BeautyQuestsDestinationProvider());
    getServer().getPluginManager().registerEvents(new BeautyQuestsStageListener(prefs), this);

    getLogger().info("OdysseyBeautyQuests enabled.");
  }
}
