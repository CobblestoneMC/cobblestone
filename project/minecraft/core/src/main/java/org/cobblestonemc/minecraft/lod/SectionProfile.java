/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.lod;

import java.util.List;

/**
 * What one 16³ section looks like to the coarse search: its connected open regions, and how far
 * each medium gets across each of them.
 *
 * <p>The whole point is to answer "roughly what does crossing here cost, for an agent who can do
 * these things" without reading 4 096 blocks again. A section is summarized once, and that summary
 * is what the coarse search reasons over — several thousand times faster than the terrain it
 * describes, which is what makes searching a long route affordable at all.
 */
public final class SectionProfile {

  /**
   * The shape of a stored profile, bumped whenever that shape changes.
   *
   * <p>Coverage is a dense array indexed by {@link Medium#ordinal()} and sized by {@link
   * Medium#COUNT}, so adding, removing or reordering a medium changes what every byte of a stored
   * profile means without changing its length in any way a reader could notice. Anything that
   * persists or caches profiles must record this number beside them and discard what it holds when
   * it moves; the alternative is a cache that silently answers with the wrong terrain.
   *
   * <p>Nothing has shipped, so this stays at 1 and a change to the mediums is answered by throwing
   * the derived data away rather than by carrying a number forward. Once there are installs in the
   * field that cannot simply be told to re-profile, it starts moving.
   */
  public static final int FORMAT_VERSION = 1;

  /**
   * The most components a section keeps.
   *
   * <p>Pathological terrain — a lattice of small sealed pockets — can have dozens, and keeping them
   * all would make the record variable-length for a case that never matters. Beyond the cap the
   * smallest are merged and the profile says so, so a consumer can tell a precise answer from an
   * approximate one.
   */
  public static final int MAX_COMPONENTS = 4;

  /** The six faces of a section, and the axis each lies on. */
  public enum Face {
    /** Towards negative X. */
    WEST(Axis.X, -1),
    /** Towards positive X. */
    EAST(Axis.X, 1),
    /** Downwards. */
    DOWN(Axis.Y, -1),
    /** Upwards. */
    UP(Axis.Y, 1),
    /** Towards negative Z. */
    NORTH(Axis.Z, -1),
    /** Towards positive Z. */
    SOUTH(Axis.Z, 1);

    /** Every face, cached. */
    public static final Face[] ALL = values();

    private final Axis axis;
    private final int sign;

    Face(Axis axis, int sign) {
      this.axis = axis;
      this.sign = sign;
    }

    /**
     * Returns the axis this face lies on.
     *
     * @return the axis
     */
    public Axis axis() {
      return axis;
    }

    /**
     * Returns {@code -1} for the low face of its axis and {@code +1} for the high one.
     *
     * @return the sign
     */
    public int sign() {
      return sign;
    }

    /**
     * Returns the face on the other side of the section.
     *
     * @return the opposite face
     */
    public Face opposite() {
      return ALL[ordinal() ^ 1];
    }
  }

  /** The three axes coverage is measured along, in {@link Axis} order. */
  public enum Axis {
    /** East–west. */
    X,
    /** Vertical. */
    Y,
    /** North–south. */
    Z;

    /** Every axis, cached. */
    public static final Axis[] ALL = values();
  }

  private final List<Component> components;
  private final Component whole;
  private final boolean merged;

  SectionProfile(List<Component> components, Component whole, boolean merged) {
    this.components = List.copyOf(components);
    this.whole = whole;
    this.merged = merged;
  }

  /**
   * Returns the section summarized as a single component: every open cell, whichever pocket it is
   * in.
   *
   * <p><b>This is what the coarse graph searches over</b> — one node per section, not one per
   * component.
   *
   * <p>Splitting a section into components makes the graph refuse to route through a wall, which
   * raises the estimate towards the truth. That sounds strictly better and is not, for two reasons.
   * An estimate that is too <em>low</em> only costs expanded nodes, while one that is too high
   * distorts which route A* prefers — and measurement says this layer's estimate is already several
   * times too high, so anything that raises it further is pushing into the error we have. And a
   * wall between two components is usually <em>minable</em>; splitting the graph there forbids
   * digging through, which is a route Cobblestone genuinely offers.
   *
   * <p>The split is still computed, and {@link #components()} still reports it, so the two models
   * can be compared on real terrain rather than argued about.
   *
   * @return the whole section as one component
   */
  public Component whole() {
    return whole;
  }

  /**
   * Returns the section's components, largest first.
   *
   * @return the components
   */
  public List<Component> components() {
    return components;
  }

  /**
   * Returns whether components were merged to fit {@link #MAX_COMPONENTS}, making this profile
   * coarser than usual.
   *
   * @return {@code true} if merged
   */
  public boolean merged() {
    return merged;
  }

  /**
   * Returns whether the section has no open space at all — solid rock, or solid anything.
   *
   * <p>Such a section still has a component: it can be mined through, and a coarse search that
   * treated solid ground as absent would never consider digging.
   *
   * @return {@code true} if nothing can be occupied without breaking it
   */
  public boolean solid() {
    return components.size() == 1 && components.get(0).openVolume() == 0;
  }

  @Override
  public String toString() {
    return "SectionProfile[components=" + components.size() + (merged ? ", merged]" : "]");
  }

  /**
   * One connected open region of a section, and how far each medium reaches across it.
   *
   * <p><b>Coverage is a fraction of crossing <em>distance</em>, not of volume.</b> A section half
   * full of water as one deep pool carries a boat across far less of it than one half full of water
   * as a river running its length, and it is the second that makes the crossing cheap. So coverage
   * counts the 16 slices perpendicular to an axis that contain a cell the medium can occupy.
   *
   * <p>⚠️ This is a marginal statistic and is known to be optimistic: 50% boat and 50% walk on one
   * axis does not prove the water and the land connect to each other. That is accepted, because
   * over-optimism in a heuristic costs expanded nodes and never costs correctness, while the
   * connectivity search that would detect it is fiddly and would have to be re-implemented for
   * every level of aggregation.
   */
  public static final class Component {

    private final byte[] coverage;
    private final int faceMask;
    private final int openVolume;
    private final double averageBreakTime;

    Component(byte[] coverage, int faceMask, int openVolume, double averageBreakTime) {
      this.coverage = coverage;
      this.faceMask = faceMask;
      this.openVolume = openVolume;
      this.averageBreakTime = averageBreakTime;
    }

    /**
     * Returns whether this component reaches the given face of its section.
     *
     * <p>What makes splitting a section into components worth anything. Without it, every component
     * would be assumed to touch every neighbour, and a wall down the middle — the exact thing the
     * flood fill went to the trouble of detecting — would be reconnected at the section boundary.
     *
     * @param face the face
     * @return {@code true} if any cell of this component lies on that face
     */
    public boolean touches(Face face) {
      return (faceMask & (1 << face.ordinal())) != 0;
    }

    /**
     * Returns the six-bit set of faces this component reaches.
     *
     * @return the face mask
     */
    public int faceMask() {
      return faceMask;
    }

    /**
     * Returns the fraction of slices along {@code axis} that hold a cell {@code medium} can occupy,
     * from 0 to 1.
     *
     * @param axis the axis
     * @param medium the medium
     * @return the coverage fraction
     */
    public double coverage(Axis axis, Medium medium) {
      return (coverage[axis.ordinal() * Medium.COUNT + medium.ordinal()] & 0xFF) / 16.0;
    }

    /**
     * Returns the number of cells in this component.
     *
     * @return the open volume, in blocks
     */
    public int openVolume() {
      return openVolume;
    }

    /**
     * Returns the mean break time of the blocks that would have to be broken to dig through, for
     * pricing {@link Medium#MINE}.
     *
     * @return the average break time in seconds
     */
    public double averageBreakTime() {
      return averageBreakTime;
    }

    /**
     * Returns the cheapest medium with any coverage along an axis, or {@code null} if none has.
     *
     * @param axis the axis
     * @param available the mediums the agent can use
     * @return the cheapest usable medium, or {@code null}
     */
    public Medium cheapest(Axis axis, Iterable<Medium> available) {
      Medium best = null;
      for (Medium medium : available) {
        if (coverage(axis, medium) > 0
            && (best == null || medium.baseCostPerBlock() < best.baseCostPerBlock())) {
          best = medium;
        }
      }
      return best;
    }
  }
}
