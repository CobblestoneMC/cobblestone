/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.message;

import net.kyori.adventure.text.format.TextColor;

/**
 * Cobblestone's message palette. The prefix and primary accents use {@link #PRIMARY}; input
 * parameters substituted into a message are highlighted in {@link #SECONDARY}; the rest of a line
 * takes its {@link MessageCategory} base color ({@link #INFO}/{@link #SUCCESS}/{@link #ERROR}).
 */
public final class CobblestoneColors {

  /** Primary brand accent (sky blue) — the prefix glyph and headline accents. */
  public static final TextColor PRIMARY = TextColor.color(0x4AA8FF);

  /** Secondary accent (amber) — input parameters substituted into a message. */
  public static final TextColor SECONDARY = TextColor.color(0xFFC857);

  /** Generic informational text (light gray). */
  public static final TextColor INFO = TextColor.color(0xB8B8B8);

  /** Success text (green). */
  public static final TextColor SUCCESS = TextColor.color(0x55FF55);

  /** Error text (red). */
  public static final TextColor ERROR = TextColor.color(0xFF5555);

  /** Muted framing around the prefix glyph (dark gray brackets). */
  public static final TextColor PREFIX_FRAME = TextColor.color(0x8A8A8A);

  private CobblestoneColors() {}
}
