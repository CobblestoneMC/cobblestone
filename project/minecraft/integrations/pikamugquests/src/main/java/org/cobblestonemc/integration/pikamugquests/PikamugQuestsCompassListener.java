/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.integration.pikamugquests;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.pikamug.quests.events.quest.BukkitQuestUpdateCompassEvent;
import me.pikamug.quests.quests.Quest;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.cobblestonemc.paper.plugin.api.CobblestonePaperApi;
import org.cobblestonemc.plugin.api.NavigatorSettings;

/**
 * The auto-navigation hook: Quests fires {@link BukkitQuestUpdateCompassEvent} whenever a player's
 * quest compass target changes (accepting a quest, advancing a stage, an objective moving). If that
 * quest opts into navigation ({@link QuestNavPrefs}), we start — or, because the trip carries the
 * quest's name as its stable label, <em>replace</em> — a guided Cobblestone trip to the new target.
 *
 * <p>Quests may fire this event off the main thread; every call here is thread-safe (a UUID lookup
 * and the async trip service). A per-player, per-quest dedupe skips re-searching when the target
 * block has not actually moved since the last trip we started.
 */
final class PikamugQuestsCompassListener implements Listener {

  private final QuestNavPrefs prefs;
  // uuid|questId -> the block-cell of the last target we navigated to; avoids redundant
  // re-searches.
  private final Map<String, String> lastTarget = new ConcurrentHashMap<>();

  PikamugQuestsCompassListener(QuestNavPrefs prefs) {
    this.prefs = prefs;
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onCompassUpdate(BukkitQuestUpdateCompassEvent event) {
    Quest quest = event.getQuest();
    if (quest == null || !prefs.autoNavigate(quest.getId())) {
      return;
    }
    Location target = event.getNewCompassTarget();
    UUID uuid = event.getQuester().getUUID();
    String dedupeKey = uuid + "|" + quest.getId();
    if (target == null || target.getWorld() == null) {
      lastTarget.remove(
          dedupeKey); // quest has no location now; a later target will navigate afresh
      return;
    }
    if (cell(target).equals(lastTarget.put(dedupeKey, cell(target)))) {
      return; // same block as the trip we already started for this quest
    }
    Player player = Bukkit.getPlayer(uuid);
    if (player == null) {
      lastTarget.remove(dedupeKey);
      return;
    }
    NavigatorSettings settings = prefs.settings(quest.getId());
    // The quest name is the trip's stable label, so the next objective replaces this leg.
    CobblestonePaperApi.tripService()
        .navigate(
            player,
            target,
            settings,
            quest.getName(),
            reason -> {
              // No route (or the search failed): drop the dedupe so a later, reachable target
              // re-navigates.
              lastTarget.remove(dedupeKey);
            });
  }

  private static String cell(Location location) {
    return location.getWorld().getKey().asString()
        + " "
        + location.getBlockX()
        + ","
        + location.getBlockY()
        + ","
        + location.getBlockZ();
  }
}
