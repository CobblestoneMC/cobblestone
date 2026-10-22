/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.integration.bishopquests;

import org.cobblestonemc.plugin.api.MinecraftDestination;
import org.cobblestonemc.plugin.api.DestinationTree;
import com.leonardobishop.quests.common.player.QPlayer;
import com.leonardobishop.quests.common.plugin.Quests;
import com.leonardobishop.quests.common.quest.Quest;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.cobblestonemc.paper.plugin.api.Destination;
import org.cobblestonemc.paper.plugin.api.DestinationService;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.joml.Vector3i;

/**
 * Surfaces the player's started quests as navigation targets: {@code cobblestonebishopquests → quest →
 * <name>}, one leaf per started quest with a current {@code position} objective. Cobblestone roots the
 * branch at this plugin's own name, which keeps these from colliding with other integrations'
 * trees; its resolver still lets a player type just the quest name when it's unambiguous.
 * Navigating is gated by Cobblestone's {@code cobblestone.navigate.cobblestonebishopquests.quest.*} permission
 * (default-allow). The target is snapshotted when the tree is built (on the main thread, as quest
 * progress requires).
 */
final class BishopQuestsDestinationService implements DestinationService {

  static final String QUEST_KEY = "quest";

  private final Quests quests;

  BishopQuestsDestinationService(Quests quests) {
    this.quests = quests;
  }

  @Override
  public DestinationTree<MinecraftDestination<World, Vector3i>> provide(Player player) {
    QPlayer qPlayer = quests.getPlayerManager().getPlayer(player.getUniqueId());
    if (qPlayer == null) {
      return null;
    }
    var questNode = Destination.tree();
    boolean any = false;
    Set<String> taken = new HashSet<>();
    for (Quest quest : qPlayer.getQuestProgressFile().getStartedQuests()) {
      Location target = QuestTargets.current(qPlayer, quest);
      if (target == null) {
        continue; // no current position objective — nothing to walk to
      }
      String label = quest.getId();
      String key = slug(label);
      for (int n = 2; !taken.add(key); n++) {
        key = slug(label) + "-" + n; // two quests that slug alike
      }
      questNode.destination(key, () -> Destination.at(target, label));
      any = true;
    }
    return any ? Destination.tree().subtree(QUEST_KEY, questNode).build() : null;
  }

  /** A single command token from a quest id: lowercase, non-alphanumeric runs become one dash. */
  private static String slug(String id) {
    String slug = id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    return slug.isEmpty() ? "quest" : slug;
  }
}
