/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.integration.bishopquests;

import com.leonardobishop.quests.common.plugin.Quests;
import net.whimxiqal.odyssey.paper.plugin.api.Odyssey;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Entry point for the LMBishop Quests integration. Resolves the Quests API handle, then registers —
 * as an ordinary Bukkit service — a destination provider ({@code /navigate quests quest <name>})
 * and, as a listener, the tracking hook that auto-navigates a player to a quest's position
 * objective when they track it. Odyssey discovers the destination provider automatically.
 *
 * <p>The {@code instanceof Quests} check also disambiguates from PikaMug's Quests (both use the
 * plugin name {@code Quests}, but only one can run at a time): if the installed {@code Quests}
 * isn't LMBishop's, this integration disables itself cleanly.
 */
public final class OdysseyBishopQuestsPlugin extends JavaPlugin {

  @Override
  public void onEnable() {
    Plugin questsPlugin = getServer().getPluginManager().getPlugin("Quests");
    if (!(questsPlugin instanceof Quests quests)) {
      getLogger().severe("LMBishop Quests not found; disabling OdysseyBishopQuests.");
      getServer().getPluginManager().disablePlugin(this);
      return;
    }

    saveDefaultConfig();
    QuestNavPrefs prefs = new QuestNavPrefs(getConfig());

    Odyssey.register(this, new BishopQuestsDestinationService(quests));
    getServer()
        .getPluginManager()
        .registerEvents(new BishopQuestsTrackListener(quests, prefs), this);

    getLogger().info("OdysseyBishopQuests enabled.");
  }
}
