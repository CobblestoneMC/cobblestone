/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.sidebar;

import java.util.List;
import net.kyori.adventure.text.Component;

/**
 * One player's sidebar, as the platform draws it (a scoreboard objective in the sidebar slot). The
 * platform-neutral {@link TripSidebar} decides what it says and when; an implementation only puts
 * lines on screen. Opened when the player's first line is drawn and closed once nothing is left to
 * show; never reused after {@link #close()}.
 */
public interface SidebarDisplay {

  /**
   * Shows {@code lines} top to bottom under {@code title}, replacing whatever was shown before.
   *
   * @param title the sidebar heading
   * @param lines the rows, at most 15 (the most a sidebar can show)
   */
  void render(Component title, List<Component> lines);

  /**
   * Removes the sidebar, giving the player back whatever they saw before it was opened. Safe to
   * call after the player has left.
   */
  void close();
}
