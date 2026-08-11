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
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import net.whimxiqal.odyssey.paper.plugin.api.PaperDestination;
import net.whimxiqal.odyssey.paper.plugin.api.PaperDestinationProvider;
import net.whimxiqal.odyssey.paper.plugin.api.PaperDestinationTree;
import net.whimxiqal.odyssey.plugin.api.DestinationTree;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.joml.Vector3i;

/**
 * Surfaces the player's started quests as navigation targets: {@code quest → <name>}, one leaf per
 * started quest whose current stage has a precise location. Navigating to one is gated by Odyssey's
 * {@code odyssey.navigate.quest.*} permission (default-allow). The target is snapshotted when the
 * tree is built (on the main thread, as {@code getLocated} requires); advancing a stage is picked
 * up the next time destinations are resolved.
 */
final class BeautyQuestsDestinationProvider implements PaperDestinationProvider {

  static final String TREE_KEY = "quest";

  @Override
  public Collection<DestinationTree<World, Vector3i>> provide(Player player) {
    PlayerAccount account = PlayersManager.getPlayerAccount(player);
    if (account == null) {
      return List.of();
    }
    PaperDestinationTree root = PaperDestinationTree.node(TREE_KEY);
    boolean any = false;
    for (Quest quest : QuestsAPI.getAPI().getQuestsManager().getQuestsStarted(account)) {
      Location target = QuestTargets.current(account, quest);
      if (target == null) {
        continue; // current stage has no precise location — nothing to walk to
      }
      String label = label(quest);
      root.leaf(slug(label), PaperDestination.at(target, label));
      any = true;
    }
    return any ? List.of(root.build()) : List.of();
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
