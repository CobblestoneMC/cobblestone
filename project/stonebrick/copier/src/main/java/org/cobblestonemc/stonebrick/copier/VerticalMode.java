/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.copier;

import org.cobblestonemc.stonebrick.format.Sbc;

/**
 * How much of a chunk column to capture.
 *
 * <p>The single biggest lever on corpus size: a full overworld column is 24 cubes, and a scenario
 * about walking across the surface has use for three of them. Capturing the rest costs six times
 * the disk and six times the file bytes for terrain no search will ever read.
 */
public sealed interface VerticalMode {

  /**
   * Returns the inclusive range of section indices to capture for a column whose terrain surface is
   * at {@code surfaceY}.
   *
   * @param surfaceY the highest non-air block's Y in this column, or the world floor if none
   * @param minSection the lowest section index the world has
   * @param maxSection the highest section index the world has
   * @return {@code [firstSection, lastSection]}, inclusive
   */
  int[] sections(int surfaceY, int minSection, int maxSection);

  /**
   * A band that follows each column's own terrain height.
   *
   * <p><b>Following rather than fixed is what makes this cheap.</b> A fixed band sized to contain
   * the tallest point of a mountain route wastes most of its volume everywhere else along that
   * route; a following band is uniformly thin, and a route over a mountain costs no more than one
   * across a plain.
   *
   * @param below blocks to capture beneath the surface
   * @param above blocks to capture above the surface
   */
  record Surface(int below, int above) implements VerticalMode {

    @Override
    public int[] sections(int surfaceY, int minSection, int maxSection) {
      int first = Math.max(minSection, Sbc.sectionOf(surfaceY - below));
      int last = Math.min(maxSection, Sbc.sectionOf(surfaceY + above));
      return new int[] {first, Math.max(first, last)};
    }
  }

  /**
   * A fixed absolute band, for scenarios that care about a particular depth rather than about the
   * surface — a cave system at a known Y, or the Nether roof.
   *
   * @param minY the lowest world Y to capture
   * @param maxY the highest world Y to capture
   */
  record Range(int minY, int maxY) implements VerticalMode {

    @Override
    public int[] sections(int surfaceY, int minSection, int maxSection) {
      int first = Math.max(minSection, Sbc.sectionOf(minY));
      int last = Math.min(maxSection, Sbc.sectionOf(maxY));
      return new int[] {first, Math.max(first, last)};
    }
  }

  /** The whole column, floor to ceiling. For digging and cave scenarios. */
  record Full() implements VerticalMode {

    @Override
    public int[] sections(int surfaceY, int minSection, int maxSection) {
      return new int[] {minSection, maxSection};
    }
  }

  /**
   * Returns the default: the whole column.
   *
   * <p>Trimming is a real lever on a capture of thousands of columns, and worth nothing below that.
   * Since captures are machine-local rather than version-controlled, a scenario-sized capture costs
   * a few megabytes either way — while a band too thin for wherever the search decides to go
   * produces a degenerate run, and there is no way to predict in advance how deep a search will go.
   * Ask for a band deliberately, when the capture is big enough to care.
   *
   * @return the default vertical mode
   */
  static VerticalMode defaultMode() {
    return new Full();
  }

  /**
   * Renders this mode the way {@code /copier} spells it, so an estimate report can be pasted back
   * as a command.
   *
   * @return the command-line form
   */
  default String toCommandArguments() {
    return switch (this) {
      case Surface surface -> "--surface " + surface.below() + " " + surface.above();
      case Range range -> "--y " + range.minY() + " " + range.maxY();
      case Full ignored -> "--full";
    };
  }
}
