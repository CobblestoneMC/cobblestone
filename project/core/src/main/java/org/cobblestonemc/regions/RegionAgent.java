/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.regions;

/**
 * One traveler, as the region layer sees it: which classes of terrain it can be in, and what
 * crossing a region of each costs it.
 *
 * <p>The only place the traveler meets the {@link RegionGraph}. The graph knows terrain and is
 * shared by every agent; an agent knows nothing of the graph.
 *
 * <p><b>Every crossing costs at least {@link #cheapest()} per block of straight-line distance.</b>
 * The backward search focuses itself with that bound, and a crossing priced below it would let the
 * search settle a portal before a cheaper route to it had been seen.
 *
 * @param <K> the terrain's classes
 */
public interface RegionAgent<K extends Enum<K> & RegionKind> {

  /**
   * Returns whether the agent can be inside a region of this class.
   *
   * @param clazz the class
   * @return {@code true} if usable
   */
  boolean usable(K clazz);

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
  double traverse(K clazz, double fx, double fy, double fz, double tx, double ty, double tz);

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

  /**
   * Returns what a fall adds to crossing the region it lands in, or {@link
   * Double#POSITIVE_INFINITY} if this agent cannot take it.
   *
   * <p>The terrain says where a body lands; whether it survives, and at what cost, is the agent's:
   * a player in Feather Falling boots takes falls another would not.
   *
   * @param clazz the class of the region landed in
   * @param depth how far the body falls, in blocks
   * @return the cost the fall adds, at least 0
   */
  double fall(K clazz, int depth);
}
