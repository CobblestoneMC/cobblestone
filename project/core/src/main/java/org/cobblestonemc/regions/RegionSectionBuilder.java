/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.regions;

import java.lang.reflect.Array;
import java.util.Arrays;
import org.cobblestonemc.DomainBounds;
import org.jetbrains.annotations.Nullable;

/**
 * Turns one section of terrain into a {@link RegionSection}: classify every cell, flood-fill it
 * into regions, and record where regions inside it touch.
 *
 * <p><b>Three passes.</b>
 *
 * <ol>
 *   <li><b>Classify</b> gives every cell one class, by the {@link RegionTerrain}.
 *   <li><b>Fill</b> groups touching cells of one family into regions, refusing a cell whose walk
 *       from the region's seed is much longer than the straight line ({@link
 *       RegionSpec#detourRatio()}), because a region is priced in straight lines. A family is
 *       usually one class; the terrain gives a region filled from a larger one its class from the
 *       mix of its cells.
 *   <li><b>Contacts</b> scans for neighboring cells in different regions — and for walking steps a
 *       level up or down, and ledges a body can drop from — and turns each touching pair into a
 *       portal whose box covers every cell where they meet. The rules are {@link RegionJoins}, the
 *       same a graph joins sections by. A fall that leaves through the floor is kept as an exit,
 *       for the graph to follow down.
 * </ol>
 *
 * <p><b>Change the rules, bump {@link #FILL_VERSION} or the terrain's version.</b> A built section
 * is a fact about the rules it was built by; one built under others is a different graph.
 *
 * <p>Not thread-safe: it reuses a section's worth of scratch between builds. One per thread.
 *
 * @param <C> the platform's terrain
 * @param <K> the terrain's classes
 */
public final class RegionSectionBuilder<C, K extends Enum<K> & RegionKind> {

  /**
   * The version of how this fills regions and finds contacts, apart from the terrain's own rules.
   */
  public static final int FILL_VERSION = 6;

  /**
   * Blocks of detour allowed regardless of distance, so a seed's immediate neighbors never split.
   */
  private static final double DETOUR_SLACK = 3.0;

  private static final int[][] HORIZONTAL = RegionSection.DIRECTIONS;

  private final RegionSpec<C, K> spec;
  private final RegionTerrain<C, K> terrain;
  private final int shift;
  private final int width;
  private final int mask;
  private final int area;
  private final int volume;

  // --- scratch, reused between builds ---
  private final K[] classified;
  private final byte[] cls;

  /** Each cell's family, by which the fill joins cells; see {@link RegionTerrain#family}. */
  private final byte[] family;

  /** The cells of the region being filled, by class. */
  private final int[] counts;

  private final int[] regionOf;
  private final int[] queue;
  private final int[] steps;
  private final RegionJoins joins;

  /** The section being built's cells, as the joining rules read them. */
  private final RegionJoins.Cells local;

  /** The section being built's lowest corner. */
  private int ox;

  private int oy;
  private int oz;

  // --- the section being built ---
  private int regionCount;
  private byte[] regionClass = new byte[256];
  private int[] regionBox = new int[256 * 6];
  private int portalCount;
  private int[] portalA = new int[256];
  private int[] portalB = new int[256];
  private byte[] portalFall = new byte[256];
  private int[] portalBox = new int[256 * 6];
  private int exitCount;
  private int[] exits = new int[64];

  /**
   * Creates a builder for one domain.
   *
   * @param spec how the domain's terrain is cut into regions
   */
  @SuppressWarnings("unchecked")
  public RegionSectionBuilder(RegionSpec<C, K> spec) {
    this.spec = spec;
    this.terrain = spec.terrain();
    this.shift = spec.shift();
    this.width = spec.width();
    this.mask = width - 1;
    this.area = width * width;
    this.volume = spec.sectionVolume();
    this.classified = (K[]) Array.newInstance(terrain.classes(), volume);
    this.cls = new byte[volume];
    this.family = new byte[volume];
    this.counts = new int[spec.classCount()];
    this.regionOf = new int[volume];
    this.queue = new int[volume];
    this.steps = new int[volume];
    this.joins =
        new RegionJoins(region -> spec.classOf(regionClass[region]), terrain.deepestFall());
    this.local =
        (x, y, z) -> {
          int lx = x - ox;
          int ly = y - oy;
          int lz = z - oz;
          if ((lx | ly | lz) < 0 || lx > mask || ly > mask || lz > mask) {
            return RegionJoins.OUTSIDE;
          }
          return regionOf[(ly << (2 * shift)) | (lz << shift) | lx];
        };
  }

  /**
   * Builds one section.
   *
   * @param sectionX the section X
   * @param sectionY the section Y
   * @param sectionZ the section Z
   * @param source the terrain the section is read from, or {@code null} if it cannot be read
   * @return the section
   */
  public RegionSection build(int sectionX, int sectionY, int sectionZ, @Nullable C source) {
    if (source == null || spec.outside(sectionX, sectionY, sectionZ)) {
      // A section that cannot be read is nothing at all: no regions, so no portals into it. The
      // search routes around it, as the fine search will have to.
      return RegionSection.empty(spec, sectionX, sectionY, sectionZ);
    }
    ox = sectionX << shift;
    oy = sectionY << shift;
    oz = sectionZ << shift;
    Arrays.fill(classified, null);
    terrain.classify(source, ox, oy, oz, spec.bounds(), classified);
    for (int cell = 0; cell < volume; cell++) {
      cls[cell] = classified[cell] == null ? -1 : (byte) classified[cell].ordinal();
    }
    if (spec.straddles(sectionX, sectionY, sectionZ)) {
      outsideTheDomain();
    }
    regionCount = 0;
    portalCount = 0;
    exitCount = 0;
    if (uniform()) {
      // Solid rock, open sky, deep water: one class edge to edge. A full cube is convex and
      // connected, so the fill would make it one region anyway, and every neighbor inside it is
      // the same region, so nothing inside it joins.
      if (cls[0] < 0) {
        return section(sectionX, sectionY, sectionZ, RegionSection.NONE, null, null);
      }
      int id = newRegion(cls[0]);
      int[] box = {ox, oy, oz, ox + mask, oy + mask, oz + mask};
      System.arraycopy(box, 0, regionBox, id * 6, 6);
      return section(sectionX, sectionY, sectionZ, id, null, null);
    }
    for (int cell = 0; cell < volume; cell++) {
      family[cell] = cls[cell] < 0 ? -1 : (byte) terrain.family(spec.classOf(cls[cell])).ordinal();
    }
    Arrays.fill(regionOf, RegionSection.NONE);
    for (int cell = 0; cell < volume; cell++) {
      if (regionOf[cell] == RegionSection.NONE && cls[cell] >= 0) {
        fill(cell);
      }
    }
    contacts();
    joins.flush(this::newPortal);
    int region = regionOf[0];
    boolean same = true;
    for (int cell = 1; cell < volume && same; cell++) {
      same = regionOf[cell] == region;
    }
    if (same) {
      return section(sectionX, sectionY, sectionZ, region, null, null);
    }
    if (regionCount < RegionSection.NARROW_NONE) {
      byte[] narrow = new byte[volume];
      for (int i = 0; i < volume; i++) {
        int r = regionOf[i];
        narrow[i] = (byte) (r == RegionSection.NONE ? RegionSection.NARROW_NONE : r);
      }
      return section(sectionX, sectionY, sectionZ, RegionSection.NONE, narrow, null);
    }
    short[] wide = new short[volume];
    for (int i = 0; i < volume; i++) {
      int r = regionOf[i];
      wide[i] = (short) (r == RegionSection.NONE ? RegionSection.WIDE_NONE : r);
    }
    return section(sectionX, sectionY, sectionZ, RegionSection.NONE, null, wide);
  }

  private RegionSection section(
      int sectionX,
      int sectionY,
      int sectionZ,
      int uniform,
      byte @Nullable [] cells,
      short @Nullable [] wideCells) {
    return new RegionSection(
        sectionX,
        sectionY,
        sectionZ,
        shift,
        uniform,
        cells,
        wideCells,
        Arrays.copyOf(regionClass, regionCount),
        Arrays.copyOf(regionBox, regionCount * 6),
        Arrays.copyOf(portalA, portalCount),
        Arrays.copyOf(portalB, portalCount),
        Arrays.copyOf(portalFall, portalCount),
        Arrays.copyOf(portalBox, portalCount * 6),
        Arrays.copyOf(exits, exitCount));
  }

  /** Makes the cells beyond any of the domain's edges belong to no region. */
  private void outsideTheDomain() {
    DomainBounds bounds = spec.bounds();
    for (int cell = 0; cell < volume; cell++) {
      int x = ox + (cell & mask);
      int y = oy + (cell >> (2 * shift));
      int z = oz + ((cell >> shift) & mask);
      if (x < bounds.minX()
          || x > bounds.maxX()
          || y < bounds.minY()
          || y > bounds.maxY()
          || z < bounds.minZ()
          || z > bounds.maxZ()) {
        cls[cell] = -1;
      }
    }
  }

  /** Whether every cell is of one class, or of none. */
  private boolean uniform() {
    byte first = cls[0];
    for (int cell = 1; cell < volume; cell++) {
      if (cls[cell] != first) {
        return false;
      }
    }
    return true;
  }

  /**
   * Flood-fills one region from a seed.
   *
   * <p>Breadth-first, so the first time a cell is reached is by its shortest walk from the seed,
   * and that is the walk the detour cap is measured on.
   */
  private void fill(int seed) {
    int c = family[seed];
    RegionKind kind = spec.classOf(c);
    boolean standing = kind.standing();
    boolean capped = kind.convex();
    int id = newRegion(c);
    Arrays.fill(counts, 0);
    int sx = seed & mask;
    int sz = (seed >> shift) & mask;
    int sy = seed >> (2 * shift);

    int head = 0;
    int tail = 0;
    queue[tail] = seed;
    steps[tail++] = 0;
    regionOf[seed] = id;
    int[] box = {
      Integer.MAX_VALUE,
      Integer.MAX_VALUE,
      Integer.MAX_VALUE,
      -Integer.MAX_VALUE,
      -Integer.MAX_VALUE,
      -Integer.MAX_VALUE
    };
    while (head < tail) {
      int cell = queue[head];
      int walked = steps[head++];
      int x = cell & mask;
      int z = (cell >> shift) & mask;
      int y = cell >> (2 * shift);
      RegionContacts.grow(box, ox + x, oy + y, oz + z);
      counts[cls[cell]]++;
      for (int[] h : HORIZONTAL) {
        int nx = x + h[0];
        int nz = z + h[1];
        if (nx < 0 || nx > mask || nz < 0 || nz > mask) {
          continue;
        }
        tail = offer(nx, y, nz, c, id, walked, tail, sx, sy, sz, capped);
        if (standing) {
          // A step up or down is a walking move, and joins two standing cells a level apart.
          if (y < mask) {
            tail = offer(nx, y + 1, nz, c, id, walked, tail, sx, sy, sz, capped);
          }
          if (y > 0) {
            tail = offer(nx, y - 1, nz, c, id, walked, tail, sx, sy, sz, capped);
          }
        }
      }
      if (y < mask) {
        tail = offer(x, y + 1, z, c, id, walked, tail, sx, sy, sz, capped);
      }
      if (y > 0) {
        tail = offer(x, y - 1, z, c, id, walked, tail, sx, sy, sz, capped);
      }
    }
    System.arraycopy(box, 0, regionBox, id * 6, 6);
    regionClass[id] = (byte) terrain.label(spec.classOf(c), counts).ordinal();
  }

  private int offer(
      int x,
      int y,
      int z,
      int c,
      int id,
      int walked,
      int tail,
      int sx,
      int sy,
      int sz,
      boolean capped) {
    int cell = (y << (2 * shift)) | (z << shift) | x;
    if (regionOf[cell] != RegionSection.NONE || family[cell] != c) {
      return tail;
    }
    if (capped) {
      // Manhattan, because the fill walks face to face and so counts Manhattan steps. Against a
      // Euclidean straight line, every open cube failed the cap towards its far corners — 45 steps
      // against 26 blocks — and open sky and water split into regions for no reason. Measured in
      // the same metric as the walk, a convex patch never splits and a cave that bends back does.
      int straight = Math.abs(x - sx) + Math.abs(y - sy) + Math.abs(z - sz);
      if (walked + 1 > spec.detourRatio() * straight + DETOUR_SLACK) {
        return tail; // left for a later seed, which will start a region of its own
      }
    }
    regionOf[cell] = id;
    queue[tail] = cell;
    steps[tail] = walked + 1;
    return tail + 1;
  }

  /**
   * Finds every contact between two regions inside the section just filled, and every ledge whose
   * fall leaves it through the floor. Joins that cross the section's faces are the graph's.
   */
  private void contacts() {
    for (int cell = 0; cell < volume; cell++) {
      int r = regionOf[cell];
      if (r == RegionSection.NONE) {
        continue;
      }
      int x = cell & mask;
      int z = (cell >> shift) & mask;
      int ly = cell >> (2 * shift);
      int gx = ox + x;
      int y = oy + ly;
      int gz = oz + z;
      // Each face once, in the positive direction.
      if (x < mask) {
        joins.touch(r, regionOf[cell + 1], gx, y, gz, gx + 1, y, gz);
      }
      if (z < mask) {
        joins.touch(r, regionOf[cell + width], gx, y, gz, gx, y, gz + 1);
      }
      if (ly < mask) {
        joins.touch(r, regionOf[cell + area], gx, y, gz, gx, y + 1, gz);
      }
      if (!joins.standing(r)) {
        continue;
      }
      for (int d = 0; d < HORIZONTAL.length; d++) {
        int nx = x + HORIZONTAL[d][0];
        int nz = z + HORIZONTAL[d][1];
        if (nx < 0 || nx > mask || nz < 0 || nz > mask) {
          continue; // across a seam; the graph finds it
        }
        // A step a level up or down out of the section reads nothing here: the graph's.
        joins.stepUp(r, gx, y, gz, local, ox + nx, oz + nz);
        joins.stepDown(r, gx, y, gz, local, ox + nx, oz + nz);
        if (joins.drop(r, gx, y, gz, local, ox + nx, oz + nz)) {
          newExit((cell << 2) | d);
        }
      }
    }
  }

  private int newRegion(int clazz) {
    if (regionCount == regionClass.length) {
      int size = regionCount * 2;
      regionClass = Arrays.copyOf(regionClass, size);
      regionBox = Arrays.copyOf(regionBox, size * 6);
    }
    int id = regionCount++;
    regionClass[id] = (byte) clazz;
    return id;
  }

  private void newPortal(int a, int b, int fall, int[] box) {
    if (portalCount == portalA.length) {
      int size = portalCount * 2;
      portalA = Arrays.copyOf(portalA, size);
      portalB = Arrays.copyOf(portalB, size);
      portalFall = Arrays.copyOf(portalFall, size);
      portalBox = Arrays.copyOf(portalBox, size * 6);
    }
    int id = portalCount++;
    portalA[id] = a;
    portalB[id] = b;
    portalFall[id] = (byte) fall;
    System.arraycopy(box, 0, portalBox, id * 6, 6);
  }

  private void newExit(int exit) {
    if (exitCount == exits.length) {
      exits = Arrays.copyOf(exits, exitCount * 2);
    }
    exits[exitCount++] = exit;
  }
}
