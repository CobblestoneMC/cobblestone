/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge.hologram;

import net.kyori.adventure.text.Component;
import org.spongepowered.math.vector.Vector3d;

/** One label placed by {@link SpongeHolograms}. Not reused after {@link #remove()}. */
public interface SpongeHologram {

  /**
   * Moves the label so its text is centered on {@code at}, in the same world.
   *
   * @param at the new center
   * @return {@code false} if it can't be moved; it should then be removed and spawned again
   */
  boolean move(Vector3d at);

  /**
   * Replaces the label's text.
   *
   * @param text the new text
   */
  void text(Component text);

  /** Removes the label. Safe to call more than once. */
  void remove();
}
