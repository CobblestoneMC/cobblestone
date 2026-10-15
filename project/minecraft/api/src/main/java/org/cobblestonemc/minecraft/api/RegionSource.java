/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.api;

/**
 * Where the {@code REGIONS} heuristic gets the terrain it reads ahead.
 *
 * <p>The heuristic summarizes chunks into regions before it can estimate across them. For a long
 * route that work is worth keeping, and worth reading back from disk rather than doing again. For a
 * short search near a player it is not: everything it needs is already in memory, and a summary
 * built from those chunks on the spot costs less than any read.
 */
public enum RegionSource {

  /**
   * The server's region stores: summaries built once, kept, and read back by every later search,
   * from disk too when the server keeps them there. Chunks a summary needs are read as the server's
   * chunk policy allows. The default.
   */
  STORED,

  /**
   * Summaries built for this search alone from chunks the server has loaded, and then dropped.
   * Nothing is read from disk or written to it, and a chunk that is not loaded is a wall to the
   * estimate, though not to the search. For short searches around a player, such as a path back to
   * a trail.
   */
  LOADED
}
