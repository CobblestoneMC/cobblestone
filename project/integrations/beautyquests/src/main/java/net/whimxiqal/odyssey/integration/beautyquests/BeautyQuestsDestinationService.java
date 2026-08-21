/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.integration.beautyquests;

import fr.skytasul.quests.api.QuestsAPI;
import fr.skytasul.quests.api.players.PlayerAccount;
import fr.skytasul.quests.api.players.PlayersManager;
import fr.skytasul.quests.api.quests.Quest;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import net.whimxiqal.odyssey.paper.plugin.api.Destination;
import net.whimxiqal.odyssey.paper.plugin.api.DestinationService;
import net.whimxiqal.odyssey.paper.plugin.api.DestinationTree;
import net.whimxiqal.odyssey.plugin.api.PlatformDestinationTree;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.joml.Vector3i;

/**
 * Surfaces the player's started quests as navigation targets: {@code beautyquests → quest →
 * <name>}, one leaf per started quest whose current stage has a precise location. The plugin-unique
 * {@code beautyquests} root keeps these from colliding with other integrations' trees; Odyssey's
 * resolver still lets a player type just the quest name when it's unambiguous. Navigating is gated
 * by Odyssey's {@code odyssey.navigate.beautyquests.quest.*} permission (default-allow). The target
 * is snapshotted when the tree is built (on the main thread, as {@code getLocated} requires);
 * advancing a stage is picked up the next time destinations are resolved.
 */
final class BeautyQuestsDestinationService implements DestinationService {

  static final String TREE_KEY = "beautyquests";
  static final String QUEST_KEY = "quest";

  @Override
  public Map<String, Supplier<PlatformDestinationTree<World, Vector3i>>> provide(Player player) {
    PlayerAccount account = PlayersManager.getPlayerAccount(player);
    if (account == null) {
      return Map.of();
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
    return any
        ? Map.of(TREE_KEY, () -> DestinationTree.builder().subtree(QUEST_KEY, quests).build())
        : Map.of();
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
