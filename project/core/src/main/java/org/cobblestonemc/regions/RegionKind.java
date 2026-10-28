/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.regions;

/**
 * How a body moves through cells of one class: what the region layer joins them by. A terrain's
 * classes are an enum implementing this ({@link RegionTerrain}).
 *
 * <p><b>The joining rules are movement rules, under gravity.</b> Y is up, and a body falls toward
 * lower Y. Cells that touch face to face are always joined. Beyond that a body that stands can step
 * a level up or down, and can drop off a ledge onto something below, which joins cells that do not
 * touch and joins them one way. A class says which of those it takes part in.
 *
 * <p>Nothing here is a cost: what crossing a class costs, and which falls a body survives, is a
 * {@link RegionAgent}'s to say.
 */
public interface RegionKind {

  /**
   * Returns whether a body's feet are in cells of this class, with something solid under them: a
   * standing cell is where steps and falls start from.
   *
   * @return {@code true} for ground
   */
  boolean standing();

  /**
   * Returns whether a standing body can step into a cell of this class a level up or down.
   *
   * @return {@code true} if it can
   */
  boolean steppable();

  /**
   * Returns whether a body falls through cells of this class.
   *
   * @return {@code true} for open space
   */
  boolean open();

  /**
   * Returns whether a body falling {@code depth} cells comes to rest in this class, and so whether
   * the fall is a portal.
   *
   * <p>Where a body lands, not whether it survives: every agent shares the graph, and which falls
   * it can take is {@link RegionAgent#fall}'s to say.
   *
   * @param depth how far the body falls, at least 1
   * @return {@code true} if the fall joins the ledge to it
   */
  boolean landing(int depth);

  /**
   * Returns whether regions of this class must be close to convex.
   *
   * <p>A region is priced in straight lines, so the fill refuses a cell whose walk from the
   * region's seed is much longer than the straight line ({@link RegionSpec#detourRatio()}). A class
   * crossed in straight lines whatever its shape, such as rock that is dug through, need not be.
   *
   * @return {@code true} if the fill caps the detour
   */
  boolean convex();
}
