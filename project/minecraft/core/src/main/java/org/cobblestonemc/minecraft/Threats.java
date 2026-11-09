/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.cobblestonemc.Cell;

/**
 * Where the dangers a route can run into were when its search began: hostile mobs, and the sculk
 * that summons a warden.
 *
 * <p>A snapshot, taken on the server thread before the search starts and read from any thread
 * after. Mobs move, so a mob's position says only roughly where trouble was; that is why it is
 * priced by a wide, gentle falloff rather than a wall. Sculk does not move.
 *
 * <p>Each kind is indexed by chunk column, so asking how near the nearest one lies looks only at
 * the few columns within reach, however many the snapshot holds.
 */
public final class Threats {

  /** No threats anywhere: every strength is 0. */
  public static final Threats NONE = new Threats(Map.of(), Map.of());

  private final Map<Long, List<Cell>> mobs;
  private final Map<Long, List<Cell>> sculk;

  private Threats(Map<Long, List<Cell>> mobs, Map<Long, List<Cell>> sculk) {
    this.mobs = mobs;
    this.sculk = sculk;
  }

  /**
   * Starts an empty snapshot.
   *
   * @return the builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Returns how near {@code at} lies to a hostile mob: 1 within a block, falling off linearly to 0
   * past {@code radius} blocks.
   *
   * @param at the cell asked about
   * @param radius how far away a mob is noticed
   * @return the strength, from 0 to 1
   */
  public double mobs(Cell at, int radius) {
    return near(mobs, at, radius);
  }

  /**
   * Returns how near {@code at} lies to a sculk shrieker that can summon a warden, or a sensor that
   * can set one off: 1 within a block, falling off linearly to 0 past {@code radius} blocks.
   *
   * @param at the cell asked about
   * @param radius how far away sculk is noticed
   * @return the strength, from 0 to 1
   */
  public double sculk(Cell at, int radius) {
    return near(sculk, at, radius);
  }

  /** Whether the snapshot holds nothing at all. */
  public boolean isEmpty() {
    return mobs.isEmpty() && sculk.isEmpty();
  }

  private static double near(Map<Long, List<Cell>> index, Cell at, int radius) {
    if (index.isEmpty() || radius <= 0) {
      return 0.0;
    }
    double nearest = Double.MAX_VALUE;
    for (int chunkX = (at.x() - radius) >> 4; chunkX <= (at.x() + radius) >> 4; chunkX++) {
      for (int chunkZ = (at.z() - radius) >> 4; chunkZ <= (at.z() + radius) >> 4; chunkZ++) {
        List<Cell> inChunk = index.get(key(chunkX, chunkZ));
        if (inChunk != null) {
          for (Cell threat : inChunk) {
            nearest = Math.min(nearest, at.distance(threat));
          }
        }
      }
    }
    if (nearest > radius) {
      return 0.0;
    }
    return Math.min(1.0, (radius + 1 - nearest) / radius);
  }

  private static long key(int chunkX, int chunkZ) {
    return ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
  }

  /** Collects a snapshot. Not thread-safe; build it on one thread, then hand the result on. */
  public static final class Builder {

    private final Map<Long, List<Cell>> mobs = new HashMap<>();
    private final Map<Long, List<Cell>> sculk = new HashMap<>();

    private Builder() {}

    /**
     * Records a hostile mob.
     *
     * @param at where it stands
     * @return this builder
     */
    public Builder mob(Cell at) {
      add(mobs, at);
      return this;
    }

    /**
     * Records a sculk shrieker that can summon a warden, or a sculk sensor.
     *
     * @param at where it is
     * @return this builder
     */
    public Builder sculk(Cell at) {
      add(sculk, at);
      return this;
    }

    private static void add(Map<Long, List<Cell>> index, Cell at) {
      index.computeIfAbsent(key(at.x() >> 4, at.z() >> 4), k -> new ArrayList<>()).add(at);
    }

    /**
     * Returns the snapshot.
     *
     * @return the threats
     */
    public Threats build() {
      if (mobs.isEmpty() && sculk.isEmpty()) {
        return NONE;
      }
      return new Threats(Map.copyOf(mobs), Map.copyOf(sculk));
    }
  }
}
