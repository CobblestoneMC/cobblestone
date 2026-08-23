/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.integration.beautyquests;

import fr.skytasul.quests.api.QuestsAPI;
import fr.skytasul.quests.api.players.PlayerAccount;
import fr.skytasul.quests.api.players.PlayersManager;
import fr.skytasul.quests.api.quests.Quest;
import java.util.Locale;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.cobblestonemc.paper.plugin.api.Destination;
import org.cobblestonemc.paper.plugin.api.DestinationService;
import org.cobblestonemc.paper.plugin.api.DestinationTree;
import org.cobblestonemc.plugin.api.PlatformDestinationTree;
import org.joml.Vector3i;

/**
 * Surfaces the player's started quests as navigation targets: {@code cobblestonebeautyquests →
 * quest → <name>}, one leaf per started quest whose current stage has a precise location.
 * Cobblestone roots the branch at this plugin's own name, which keeps these from colliding with
 * other integrations' trees; its resolver still lets a player type just the quest name when it's
 * unambiguous. Navigating is gated by Cobblestone's {@code
 * cobblestone.navigate.cobblestonebeautyquests.quest.*} permission (default-allow). The target is
 * snapshotted when the tree is built (on the main thread, as {@code getLocated} requires);
 * advancing a stage is picked up the next time destinations are resolved.
 */
final class BeautyQuestsDestinationService implements DestinationService {

  static final String QUEST_KEY = "quest";

  @Override
  public PlatformDestinationTree<World, Vector3i> provide(Player player) {
    PlayerAccount account = PlayersManager.getPlayerAccount(player);
    if (account == null) {
      return null;
    }
    DestinationTree quests = DestinationTree.builder();
    boolean any = false;
    for (Quest quest : QuestsAPI.getAPI().getQuestsManager().getQuestsStarted(account)) {
      Location target = QuestTargets.current(account, quest);
      if (target == null) {
        continue; // current stage has no precise location — nothing to walk to
      }
      String label = label(quest);
      quests.leaf(slug(label), Destination.at(target, label));
      any = true;
    }
    return any ? DestinationTree.builder().subtree(QUEST_KEY, quests).build() : null;
  }

  /** A player-facing quest label: its name, or a stable fallback from its id. */
  static String label(Quest quest) {
    String name = quest.getName();
    return name != null && !name.isBlank() ? name : "quest-" + quest.getId();
  }

  /**
   * A single command token from a quest label: lowercase, non-alphanumeric runs become one dash.
   */
  private static String slug(String label) {
    String slug =
        label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    return slug.isEmpty() ? "quest" : slug;
  }
}
