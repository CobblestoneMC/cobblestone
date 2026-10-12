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
   * Moves the label so its text is centered on {@code at}.
   *
   * @param at the new center, in the player's world
   * @return {@code false} if it can't be moved there from this thread; it should then be removed
   *     and spawned again
   */
  boolean move(Vec3 at);

  /**
   * Replaces the label's text.
   *
   * @param text the new text
   */
  void text(Component text);

  /**
   * Removes the label from the world. Safe to call more than once, and after the player has left.
   */
  void remove();
}
