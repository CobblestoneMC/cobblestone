/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.message;

import net.kyori.adventure.text.format.TextColor;

/** The tone of a message, which sets the base color of its non-parameter text. */
public enum MessageCategory {

  /** Neutral information (light gray). */
  INFO(CobblestoneColors.INFO),

  /** A successful action (green). */
  SUCCESS(CobblestoneColors.SUCCESS),

  /** An error or failure (red). */
  ERROR(CobblestoneColors.ERROR);

  private final TextColor baseColor;

  MessageCategory(TextColor baseColor) {
    this.baseColor = baseColor;
  }

  /**
   * Returns the base color for this category's literal text.
   *
   * @return the base color
   */
  public TextColor baseColor() {
    return baseColor;
  }
}
