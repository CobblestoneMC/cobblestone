/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.integration.beautyquests;

import fr.skytasul.quests.api.players.PlayerAccount;
import fr.skytasul.quests.api.players.PlayerQuestDatas;
import fr.skytasul.quests.api.quests.Quest;
import fr.skytasul.quests.api.quests.branches.EndingStage;
import fr.skytasul.quests.api.quests.branches.QuestBranch;
import fr.skytasul.quests.api.stages.AbstractStage;
import fr.skytasul.quests.api.stages.StageController;
import fr.skytasul.quests.api.stages.types.Locatable;
import java.util.List;
import org.bukkit.Location;

/**
 * Resolves the navigable location of a BeautyQuests stage. A stage is navigable only when it is a
 * {@link Locatable.PreciseLocatable} — a single, player-independent point such as a "reach
 * location" or an NPC to talk to; {@code MultipleLocatable} stages (kill any of a mob within a
 * region) have no single target and are skipped. Every method here must be called on the main
 * thread.
 */
final class QuestTargets {

  private QuestTargets() {}

  /**
   * The current stage location for a started {@code quest} and {@code account}, or {@code null}.
   */
  static Location current(PlayerAccount account, Quest quest) {
    PlayerQuestDatas data = account.getQuestDatasIfPresent(quest);
    if (data == null) {
      return null;
    }
    QuestBranch branch = quest.getBranchesManager().getBranch(data.getBranch());
    if (branch == null) {
      return null;
    }
    if (data.isInEndingStages()) {
      // Ending stages run in parallel; guide to the first one that has a precise location.
      for (EndingStage ending : branch.getEndingStages()) {
        Location location = locationOf(ending.getStage());
        if (location != null) {
          return location;
        }
      }
      return null;
    }
    int index = data.getStage();
    List<StageController> regular = branch.getRegularStages();
    if (index < 0 || index >= regular.size()) {
      return null;
    }
    return locationOf(regular.get(index));
  }

  /** The precise location of {@code controller}'s stage, or {@code null} if it has none. */
  static Location locationOf(StageController controller) {
    if (controller == null) {
      return null;
    }
    AbstractStage stage = controller.getStage();
    if (!(stage instanceof Locatable.PreciseLocatable precise)) {
      return null;
    }
    Locatable.Located located = precise.getLocated();
    if (located == null) {
      return null;
    }
    Location location = located.getLocation();
    return location.getWorld() != null ? location : null;
  }
}
