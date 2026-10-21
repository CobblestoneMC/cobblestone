/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.hologram;

import net.kyori.adventure.text.Component;
import org.cobblestonemc.plugin.navigator.Vec3;

/**
 * One floating label as placed by the platform, seen only by its player. Not reused after {@link
 * #remove()}.
 */
public interface Hologram {

  /**
   * Replaces the label's text.
   *
   * @param text the new text
   */
  void text(Component text);

  /**
   * Moves the label, gliding there over a couple of ticks.
   *
   * @param to the new center of its text
   */
  void move(Vec3 to);

  /**
   * Removes the label from the world. Safe to call more than once, and after the player has left.
   */
  void remove();
}
