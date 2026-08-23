/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.integration.pikamugquests;

import java.util.LinkedList;
import me.pikamug.quests.player.Quester;
import me.pikamug.quests.quests.Quest;
import me.pikamug.quests.quests.components.Stage;
import org.bukkit.Location;

/**
 * Resolves the current navigable location of a quest for a given quester — the on-demand
 * counterpart to the compass event (which hands us its target directly). We look at the quester's
 * current stage and take its first locatable objective: a "reach location" first, else a "kill
 * within a location". Returns {@code null} when the quest's current stage has nowhere concrete to
 * walk to.
 */
final class QuestTargets {

  private QuestTargets() {}

  /** The current target location for {@code quest} and {@code quester}, or {@code null}. */
  static Location current(Quester quester, Quest quest) {
    Stage stage = quester.getCurrentStage(quest);
    if (stage == null || !stage.hasLocatableObjective()) {
      return null;
    }
    Location reach = first(stage.getLocationsToReach());
    if (reach != null) {
      return reach;
    }
    return first(stage.getLocationsToKillWithin());
  }

  private static Location first(LinkedList<?> locations) {
    if (locations == null || locations.isEmpty()) {
      return null;
    }
    Object candidate = locations.getFirst();
    return candidate instanceof Location location && location.getWorld() != null ? location : null;
  }
}
