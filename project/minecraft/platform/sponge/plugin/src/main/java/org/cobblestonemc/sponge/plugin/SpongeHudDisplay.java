/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge.plugin;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.cobblestonemc.plugin.hud.HudDisplay;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import org.spongepowered.api.scoreboard.Score;
import org.spongepowered.api.scoreboard.ScoreFormat;
import org.spongepowered.api.scoreboard.Scoreboard;
import org.spongepowered.api.scoreboard.criteria.Criteria;
import org.spongepowered.api.scoreboard.displayslot.DisplaySlots;
import org.spongepowered.api.scoreboard.objective.Objective;

/**
 * A trip HUD drawn in the scoreboard sidebar. The player is given their own scoreboard while it is
 * open.
 */
final class SpongeHudDisplay implements HudDisplay {

  private static final String OBJECTIVE = "cobblestone_trips";

  private final ServerPlayer player;
  private final Scoreboard previous;
  private final Scoreboard board;
  private final Objective objective;
  private Component shownTitle;
  private final List<Component> shownLines = new ArrayList<>();

  SpongeHudDisplay(ServerPlayer player) {
    this.player = player;
    this.previous = player.scoreboard();
    this.board = Scoreboard.builder().build();
    this.objective =
        Objective.builder()
            .name(OBJECTIVE)
            .criterion(Criteria.DUMMY)
            .displayName(Component.empty())
            .build();
    board.addObjective(objective);
    board.updateDisplaySlot(objective, DisplaySlots.SIDEBAR);
    player.setScoreboard(board);
  }

  @Override
  public void render(Component title, List<Component> lines) {
    if (!title.equals(shownTitle)) {
      objective.setDisplayName(title);
      shownTitle = title;
    }
    // Rows are ordered by score, so every row moves when the count changes.
    boolean resized = lines.size() != shownLines.size();
    for (int i = 0; i < lines.size(); i++) {
      Score score = objective.findOrCreateScore(entry(i));
      if (resized) {
        score.setScore(lines.size() - i);
        score.setNumberFormat(ScoreFormat.blank());
      }
      if (resized || !lines.get(i).equals(shownLines.get(i))) {
        score.setDisplay(lines.get(i));
      }
    }
    for (int i = lines.size(); i < shownLines.size(); i++) {
      objective.removeScore(entry(i));
    }
    shownLines.clear();
    shownLines.addAll(lines);
  }

  @Override
  public void close() {
    if (player.isOnline() && player.scoreboard() == board) {
      player.setScoreboard(previous);
    }
  }

  private static String entry(int line) {
    return "cobblestone-trip-" + line;
  }
}
