/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.integration.pikamugquests;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import me.pikamug.quests.Quests;
import me.pikamug.quests.player.Quester;
import me.pikamug.quests.quests.Quest;
import net.whimxiqal.odyssey.paper.plugin.api.PaperDestination;
import net.whimxiqal.odyssey.paper.plugin.api.PaperDestinationService;
import net.whimxiqal.odyssey.paper.plugin.api.PaperDestinationTree;
import net.whimxiqal.odyssey.plugin.api.DestinationTree;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.joml.Vector3i;

/**
 * Surfaces the player's active quests as navigation targets: {@code quests → quest → <name>}, one
 * leaf per current quest whose current stage has a locatable objective (a reach-location or
 * kill-within region). The plugin-unique {@code quests} root keeps these from colliding with other
 * integrations' trees; Odyssey's resolver still lets a player type just the quest name when it's
 * unambiguous. Navigating is gated by Odyssey's {@code odyssey.navigate.pikamugquests.quest.*}
 * permission (default-allow). Targets re-resolve on query, so advancing a stage is reflected
 * without a restart.
 */
final class PikamugQuestsDestinationService implements PaperDestinationService {

  static final String TREE_KEY = "quests";
  static final String QUEST_KEY = "quest";

  private final Quests quests;

  PikamugQuestsDestinationService(Quests quests) {
    this.quests = quests;
  }

  @Override
  public Collection<DestinationTree<World, Vector3i>> provide(Player player) {
    Quester quester = quests.getQuester(player.getUniqueId());
    if (quester == null) {
      return List.of();
    }
    PaperDestinationTree questNode = PaperDestinationTree.node(QUEST_KEY);
    boolean any = false;
    for (Quest quest : quester.getCurrentQuests().keySet()) {
      if (QuestTargets.current(quester, quest) == null) {
        continue; // no locatable objective in the current stage — nothing to walk to
      }
      String label = quest.getName();
      questNode.leaf(
          slug(label),
          () ->
              PaperDestination.at(
                  QuestTargets.current(quests.getQuester(player.getUniqueId()), quest), label));
      any = true;
    }
    return any
        ? List.of(PaperDestinationTree.node(TREE_KEY).subtree(questNode).build())
        : List.of();
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
