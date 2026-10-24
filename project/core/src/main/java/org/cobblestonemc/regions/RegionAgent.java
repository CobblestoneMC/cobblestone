/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.regions;

/**
 * One traveller, as the region layer sees it: which classes of terrain it can be in, and what
 * crossing a region of each costs it.
 *
 * <p>The only place the traveller meets the {@link RegionGraph}. The graph knows terrain and is
 * shared by every agent; an agent knows nothing of the graph.
 *
 * <p><b>Every crossing costs at least {@link #cheapest()} per block of straight-line distance.</b>
 * The backward search focuses itself with that bound, and a crossing priced below it would let the
 * search settle a portal before a cheaper route to it had been seen.
 */
public interface RegionAgent {

  /**
   * Returns whether the agent can be inside a region of this class.
   *
   * @param clazz the class
   * @return {@code true} if usable
   */
  boolean usable(int clazz);

  /**
   * Returns the seconds to cross a region of this class in a straight line from one point to
   * another.
   *
   * @param clazz the class
   * @param fx where the crossing starts, X
   * @param fy where the crossing starts, Y
   * @param fz where the crossing starts, Z
   * @param tx where it ends, X
   * @param ty where it ends, Y
   * @param tz where it ends, Z
   * @return the cost in seconds
   */
  double traverse(int clazz, double fx, double fy, double fz, double tx, double ty, double tz);

  /**
   * Returns the least any crossing can cost per block.
   *
   * @return the cheapest rate, in seconds per block
   */
  double cheapest();

  /**
   * Returns what a block costs where the graph offers the agent no way through: the most an answer
   * is worth searching for, per block of distance.
   *
   * @return the fallback rate, in seconds per block
   */
  double fallback();
}
