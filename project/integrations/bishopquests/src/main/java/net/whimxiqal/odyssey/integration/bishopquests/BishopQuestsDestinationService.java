/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.integration.bishopquests;

import com.leonardobishop.quests.common.player.QPlayer;
import com.leonardobishop.quests.common.plugin.Quests;
import com.leonardobishop.quests.common.quest.Quest;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import net.whimxiqal.odyssey.paper.plugin.api.PaperDestination;
import net.whimxiqal.odyssey.paper.plugin.api.PaperDestinationService;
import net.whimxiqal.odyssey.paper.plugin.api.PaperDestinationTree;
import net.whimxiqal.odyssey.plugin.api.DestinationTree;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.joml.Vector3i;

/**
 * Surfaces the player's started quests as navigation targets: {@code quests → quest → <name>}, one
 * leaf per started quest with a current {@code position} objective. The plugin-unique {@code
 * quests} root (LMBishop's plugin id) keeps these from colliding with other integrations' trees;
 * Odyssey's resolver still lets a player type just the quest name when it's unambiguous. Navigating
 * is gated by Odyssey's {@code odyssey.navigate.quests.quest.*} permission (default-allow). The
 * target is snapshotted when the tree is built (on the main thread, as quest progress requires).
 */
final class BishopQuestsDestinationService implements PaperDestinationService {

  static final String TREE_KEY = "quests";
  static final String QUEST_KEY = "quest";

  private final Quests quests;

  BishopQuestsDestinationService(Quests quests) {
    this.quests = quests;
  }

  @Override
  public Collection<DestinationTree<World, Vector3i>> provide(Player player) {
    QPlayer qPlayer = quests.getPlayerManager().getPlayer(player.getUniqueId());
    if (qPlayer == null) {
      return List.of();
    }
    PaperDestinationTree questNode = PaperDestinationTree.node(QUEST_KEY);
    boolean any = false;
    for (Quest quest : qPlayer.getQuestProgressFile().getStartedQuests()) {
      Location target = QuestTargets.current(qPlayer, quest);
      if (target == null) {
        continue; // no current position objective — nothing to walk to
      }
      String label = quest.getId();
      questNode.leaf(slug(label), PaperDestination.at(target, label));
      any = true;
    }
    return any
        ? List.of(PaperDestinationTree.node(TREE_KEY).subtree(questNode).build())
        : List.of();
  }

  /** A single command token from a quest id: lowercase, non-alphanumeric runs become one dash. */
  private static String slug(String id) {
    String slug = id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    return slug.isEmpty() ? "quest" : slug;
  }
}
