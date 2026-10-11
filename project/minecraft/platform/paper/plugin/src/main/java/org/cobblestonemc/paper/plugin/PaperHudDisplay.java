/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper.plugin;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.cobblestonemc.plugin.hud.HudDisplay;

/**
 * A trip HUD drawn in the scoreboard sidebar. The player is given their own scoreboard while it is
 * open.
 */
final class PaperHudDisplay implements HudDisplay {

  private static final String OBJECTIVE = "cobblestone_trips";

  private final Player player;
  private final Scoreboard previous;
  private final Scoreboard board;
  private final Objective objective;
  private Component shownTitle;
  private final List<Component> shownLines = new ArrayList<>();

  PaperHudDisplay(Player player) {
    this.player = player;
    this.previous = player.getScoreboard();
    this.board = Bukkit.getScoreboardManager().getNewScoreboard();
    this.objective = board.registerNewObjective(OBJECTIVE, Criteria.DUMMY, Component.empty());
    objective.setDisplaySlot(DisplaySlot.SIDEBAR);
    player.setScoreboard(board);
  }

  @Override
  public void render(Component title, List<Component> lines) {
    if (!title.equals(shownTitle)) {
      objective.displayName(title);
      shownTitle = title;
    }
    // Rows are ordered by score, so every row moves when the count changes.
    boolean resized = lines.size() != shownLines.size();
    for (int i = 0; i < lines.size(); i++) {
      Score score = objective.getScore(entry(i));
      if (resized) {
        score.setScore(lines.size() - i);
        score.numberFormat(NumberFormat.blank());
      }
      if (resized || !lines.get(i).equals(shownLines.get(i))) {
        score.customName(lines.get(i));
      }
    }
    for (int i = lines.size(); i < shownLines.size(); i++) {
      board.resetScores(entry(i));
    }
    shownLines.clear();
    shownLines.addAll(lines);
  }

  @Override
  public void close() {
    if (player.isOnline() && player.getScoreboard() == board) {
      player.setScoreboard(previous);
    }
  }

  private static String entry(int line) {
    return "cobblestone-trip-" + line;
  }
}
