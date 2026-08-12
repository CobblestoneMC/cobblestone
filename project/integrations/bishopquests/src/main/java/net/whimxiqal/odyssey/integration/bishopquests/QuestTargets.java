/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.integration.bishopquests;

import com.leonardobishop.quests.common.player.QPlayer;
import com.leonardobishop.quests.common.player.questprogressfile.QuestProgress;
import com.leonardobishop.quests.common.player.questprogressfile.TaskProgress;
import com.leonardobishop.quests.common.quest.Quest;
import com.leonardobishop.quests.common.quest.Task;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * Resolves the navigable location of an LMBishop quest — the target of its first not-yet-completed
 * {@code position} task (go to set co-ordinates). Other task types (kill, mine, …) have no single
 * place to walk to and are ignored. Must be called on the main thread (quest progress is not
 * async-safe).
 */
final class QuestTargets {

  private static final String POSITION_TYPE = "position";

  private QuestTargets() {}

  /**
   * The current position-objective location for a started {@code quest} and {@code qPlayer}, or
   * {@code null}.
   */
  static Location current(QPlayer qPlayer, Quest quest) {
    QuestProgress progress = qPlayer.getQuestProgressFile().getQuestProgressOrNull(quest);
    if (progress == null) {
      return null;
    }
    for (Task task : quest.getTasksOfType(POSITION_TYPE)) {
      TaskProgress taskProgress = progress.getTaskProgressOrNull(task.getId());
      if (taskProgress != null && taskProgress.isCompleted()) {
        continue; // already reached this objective — guide to the next one
      }
      Location location = locationOf(task);
      if (location != null) {
        return location;
      }
    }
    return null;
  }

  /**
   * The world-anchored location of a {@code position} task, or {@code null} if incomplete/unloaded.
   */
  static Location locationOf(Task task) {
    if (!(task.getConfigValue("world") instanceof String worldName)) {
      return null; // a world-less position task matches any world; nothing concrete to walk to
    }
    World world = Bukkit.getWorld(worldName);
    if (world == null) {
      return null;
    }
    if (!(task.getConfigValue("x") instanceof Number x)
        || !(task.getConfigValue("y") instanceof Number y)
        || !(task.getConfigValue("z") instanceof Number z)) {
      return null;
    }
    // Aim at the block's centre so the trip ends on the target cell, not its corner.
    return new Location(world, x.doubleValue() + 0.5, y.doubleValue(), z.doubleValue() + 0.5);
  }
}
