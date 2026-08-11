/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.integration.beautyquests;

import fr.skytasul.quests.api.events.PlayerSetStageEvent;
import fr.skytasul.quests.api.quests.Quest;
import net.whimxiqal.odyssey.paper.plugin.api.Odyssey;
import net.whimxiqal.odyssey.plugin.api.NavigatorSettings;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * The auto-navigation hook: BeautyQuests fires {@link PlayerSetStageEvent} when a player advances
 * to a new stage. If that quest opts into navigation ({@link QuestNavPrefs}) and the new stage has
 * a precise location, we start — or, because the trip carries the quest's name as its stable label,
 * <em>replace</em> — a guided Odyssey trip to it. Unlike a compass update, this fires once per
 * stage transition, so no dedupe is needed.
 */
final class BeautyQuestsStageListener implements Listener {

  private final QuestNavPrefs prefs;

  BeautyQuestsStageListener(QuestNavPrefs prefs) {
    this.prefs = prefs;
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onSetStage(PlayerSetStageEvent event) {
    if (!event.isAccountCurrent()) {
      return; // a background profile, not the player currently in the world
    }
    Quest quest = event.getQuest();
    String questId = String.valueOf(quest.getId());
    if (!prefs.autoNavigate(questId)) {
      return;
    }
    Location target = QuestTargets.locationOf(event.getStage());
    if (target == null) {
      return; // the new stage has no precise location to guide to
    }
    Player player = event.getPlayer();
    if (player == null) {
      return;
    }
    NavigatorSettings settings = prefs.settings(questId);
    // The quest name is the trip's stable label, so the next stage replaces this leg.
    Odyssey.tripService()
        .navigate(
            player,
            target,
            settings,
            BeautyQuestsDestinationProvider.label(quest),
            reason -> {
              // No route (or the search failed): nothing to do; the next stage will try again.
            });
  }
}
