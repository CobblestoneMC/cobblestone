/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.integration.bishopquests;

import com.leonardobishop.quests.bukkit.api.event.PlayerStartTrackQuestEvent;
import com.leonardobishop.quests.common.player.QPlayer;
import com.leonardobishop.quests.common.plugin.Quests;
import com.leonardobishop.quests.common.quest.Quest;
import org.cobblestonemc.paper.plugin.api.CobblestonePluginAPI;
import org.cobblestonemc.plugin.api.NavigatorSettings;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * The auto-navigation hook: LMBishop Quests fires {@link PlayerStartTrackQuestEvent} when a player
 * tracks a quest. If that quest opts into navigation ({@link QuestNavPrefs}) and has a current
 * {@code position} objective, we start — or, because the trip carries the quest id as its stable
 * label, <em>replace</em> — a guided Cobblestone trip to it. The event doesn't carry the quest, but the
 * player's tracked-quest preference is already set when it fires, so we read it from there.
 */
final class BishopQuestsTrackListener implements Listener {

  private final Quests quests;
  private final QuestNavPrefs prefs;

  BishopQuestsTrackListener(Quests quests, QuestNavPrefs prefs) {
    this.quests = quests;
    this.prefs = prefs;
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onStartTrack(PlayerStartTrackQuestEvent event) {
    QPlayer qPlayer = event.getQuestPlayer();
    String questId = qPlayer.getPlayerPreferences().getTrackedQuestId();
    if (questId == null || !prefs.autoNavigate(questId)) {
      return;
    }
    Quest quest = quests.getQuestManager().getQuestById(questId);
    if (quest == null) {
      return;
    }
    Location target = QuestTargets.current(qPlayer, quest);
    if (target == null) {
      return; // the tracked quest has no current position objective to guide to
    }
    Player player = event.getPlayer();
    NavigatorSettings settings = prefs.settings(questId);
    // The quest id is the trip's stable label, so re-tracking (or the next objective) replaces it.
    CobblestonePluginAPI.tripService()
        .navigate(
            player,
            target,
            settings,
            questId,
            reason -> {
              // No route (or the search failed): nothing to do; the player can re-track to retry.
            });
  }
}
