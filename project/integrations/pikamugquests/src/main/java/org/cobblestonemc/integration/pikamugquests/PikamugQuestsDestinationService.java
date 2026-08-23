/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.integration.pikamugquests;

import java.util.Locale;
import me.pikamug.quests.Quests;
import me.pikamug.quests.player.Quester;
import me.pikamug.quests.quests.Quest;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.cobblestonemc.paper.plugin.api.Destination;
import org.cobblestonemc.paper.plugin.api.DestinationService;
import org.cobblestonemc.paper.plugin.api.DestinationTree;
import org.cobblestonemc.plugin.api.PlatformDestinationTree;
import org.joml.Vector3i;

/**
 * Surfaces the player's active quests as navigation targets: {@code cobblestonepikamugquests →
 * quest → <name>}, one leaf per current quest whose current stage has a locatable objective (a
 * reach-location or kill-within region). Cobblestone roots the branch at this plugin's own name,
 * which keeps these from colliding with other integrations' trees; its resolver still lets a player
 * type just the quest name when it's unambiguous. Navigating is gated by Cobblestone's {@code
 * cobblestone.navigate.cobblestonepikamugquests.quest.*} permission (default-allow). Targets
 * re-resolve on query, so advancing a stage is reflected without a restart.
 */
final class PikamugQuestsDestinationService implements DestinationService {

  static final String QUEST_KEY = "quest";

  private final Quests quests;

  PikamugQuestsDestinationService(Quests quests) {
    this.quests = quests;
  }

  @Override
  public PlatformDestinationTree<World, Vector3i> provide(Player player) {
    Quester quester = quests.getQuester(player.getUniqueId());
    if (quester == null) {
      return null;
    }
    DestinationTree questNode = DestinationTree.builder();
    boolean any = false;
    for (Quest quest : quester.getCurrentQuests().keySet()) {
      if (QuestTargets.current(quester, quest) == null) {
        continue; // no locatable objective in the current stage — nothing to walk to
      }
      String label = quest.getName();
      questNode.leaf(
          slug(label),
          () ->
              Destination.at(
                  QuestTargets.current(quests.getQuester(player.getUniqueId()), quest), label));
      any = true;
    }
    return any ? DestinationTree.builder().subtree(QUEST_KEY, questNode).build() : null;
  }

  /**
   * A single command token from a quest name: lowercase, runs of non-alphanumerics become one dash.
   */
  private static String slug(String name) {
    String slug =
        name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    return slug.isEmpty() ? "quest" : slug;
  }
}
