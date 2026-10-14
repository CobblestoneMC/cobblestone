/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper.hologram;

import net.kyori.adventure.text.Component;

/** One label placed by {@link PaperHolograms}. Not reused after {@link #remove()}. */
public interface PaperHologram {

  /**
   * Replaces the label's text.
   *
   * @param text the new text
   */
  void text(Component text);

  /** Removes the label. Safe to call more than once, and from any thread. */
  void remove();
}
