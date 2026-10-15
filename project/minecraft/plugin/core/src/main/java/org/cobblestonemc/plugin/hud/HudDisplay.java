/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.hud;

import java.util.List;
import net.kyori.adventure.text.Component;

/** One player's HUD as drawn by the platform. Not reused after {@link #close()}. */
public interface HudDisplay {

  /**
   * Shows {@code lines} top to bottom under {@code title}, replacing whatever was shown before.
   *
   * @param title the HUD heading
   * @param lines the rows, at most 15
   */
  void render(Component title, List<Component> lines);

  /**
   * Removes the HUD, restoring what the player saw before. Safe to call after the player has left.
   */
  void close();
}
