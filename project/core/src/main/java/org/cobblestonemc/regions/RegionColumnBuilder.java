/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.regions;

import java.util.Arrays;
import org.jetbrains.annotations.Nullable;

/**
 * Turns one column of terrain into a {@link RegionColumn}: classify every cell, flood-fill each
 * section into regions, and record where regions inside the column touch.
 *
 * <p><b>Three passes.</b>
 *
 * <ol>
 *   <li><b>Classify</b> gives every cell one class, by the {@link RegionTerrain}.
 *   <li><b>Fill</b> groups touching cells of one family into regions, section by section, refusing
 *       a cell whose walk from the region's seed is much longer than the straight line ({@link
 *       RegionSpec#detourRatio()}), because a region is priced in straight lines. A family is
 *       usually one class; the terrain gives a region filled from a larger one its class from the
 *       mix of its cells.
 *   <li><b>Contacts</b> scans for neighbouring cells in different regions — and for walking steps a
 *       level up or down, and ledges a body can drop from — and turns each touching pair into a
 *       portal whose box covers every cell where they meet. Which steps and drops count is the
 *       terrain's to say.
 * </ol>
 *
 * <p><b>Change the rules, bump {@link #FILL_VERSION} or the terrain's version.</b> A built column
 * is a fact about the rules it was built by; one built under others is a different graph.
 *
 * <p>Not thread-safe: it reuses a column's worth of scratch between builds. One per thread.
 *
 * @param <C> the platform's column of terrain
 */
public final class RegionColumnBuilder<C> {

  /**
   * The version of how this fills regions and finds contacts, apart from the terrain's own rules.
   */
  public static final int FILL_VERSION = 3;

  /**
   * Blocks of detour allowed regardless of distance, so a seed's immediate neighbours never split.
   */
  private static final double DETOUR_SLACK = 3.0;

  /** A section whose cells are not all one class. */
  private static final byte MIXED = -2;

  private static final int[][] HORIZONTAL = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

  private final RegionSpec<C> spec;
  private final RegionTerrain<C> terrain;
  private final int minY;
  private final int sections;
  private final int shift;
  private final int width;
  private final int mask;
  private final int area;
  private final int sectionVolume;

  // --- scratch, reused between builds ---
  private final byte[] cls;

  /** Each cell's family, by which the fill joins cells; see {@link RegionTerrain#family}. */
  private final byte[] family;

  /** The cells of the region being filled, by class. */
  private final int[] counts;

  private final byte[] sectionClass;
  private final int[] regionOf;
  private final int[] queue;
  private final int[] steps;
  private final RegionContacts contacts = new RegionContacts();

  // --- the column being built ---
  private int regionCount;
  private byte[] regionClass = new byte[256];
  private int[] regionBox = new int[256 * 6];
  private int portalCount;
  private int[] portalA = new int[256];
  private int[] portalB = new int[256];
  private boolean[] portalDirected = new boolean[256];
  private int[] portalBox = new int[256 * 6];

  /**
   * Creates a builder for one world.
   *
   * @param spec how the world's terrain is cut into regions
   */
  public RegionColumnBuilder(RegionSpec<C> spec) {
    this.spec = spec;
    this.terrain = spec.terrain();
    this.minY = spec.columnMinY();
    this.sections = spec.sections();
    this.shift = spec.shift();
    this.width = spec.width();
    this.mask = width - 1;
    this.area = width * width;
    this.sectionVolume = spec.sectionVolume();
    int volume = sections * sectionVolume;
    this.cls = new byte[volume];
    this.family = new byte[volume];
    this.counts = new int[terrain.classes()];
    this.regionOf = new int[volume];
    this.sectionClass = new byte[sections];
    this.queue = new int[sectionVolume];
    this.steps = new int[sectionVolume];
  }

  /**
   * Builds one column.
   *
   * @param columnX the column X
   * @param columnZ the column Z
   * @param terrainColumn the column of terrain, or {@code null} if it cannot be read
   * @return the column
   */
  public RegionColumn build(int columnX, int columnZ, @Nullable C terrainColumn) {
    if (terrainColumn == null) {
      // A column that cannot be read is nothing at all: no regions, so no portals into it. The
      // search routes around it, as the fine search will have to.
      return RegionColumn.empty(spec, columnX, columnZ);
    }
    terrain.classify(terrainColumn, spec.worldMinY(), spec.worldMaxY(), minY, cls);
    for (int cell = 0; cell < cls.length; cell++) {
      family[cell] = cls[cell] < 0 ? -1 : (byte) terrain.family(cls[cell]);
    }
    uniformSections();
    regionCount = 0;
    portalCount = 0;
    Arrays.fill(regionOf, RegionColumn.NONE);
    int[] uniform = new int[sections];
    int[] base = new int[sections];
    byte[][] cells = new byte[sections][];
    short[][] wideCells = new short[sections][];
    int ox = columnX << shift;
    int oz = columnZ << shift;
    for (int s = 0; s < sections; s++) {
      int first = regionCount;
      int from = s * sectionVolume;
      if (sectionClass[s] != MIXED) {
        // Solid rock, open sky, deep water: one class edge to edge. A full cube is convex and
        // connected, so the fill would make it one region anyway; this skips the fill.
        uniform[s] = RegionColumn.NONE;
        if (sectionClass[s] >= 0) {
          whole(s, ox, oz);
          uniform[s] = first;
        }
        continue;
      }
      for (int cell = from; cell < from + sectionVolume; cell++) {
        if (regionOf[cell] == RegionColumn.NONE && cls[cell] >= 0) {
          fill(cell, s, ox, oz);
        }
      }
      store(s, first, from, uniform, base, cells, wideCells);
    }
    columnContacts(ox, oz);
    contacts.flush(this::newPortal);
    return new RegionColumn(
        columnX,
        columnZ,
        minY,
        shift,
        uniform,
        base,
        cells,
        wideCells,
        Arrays.copyOf(regionClass, regionCount),
        Arrays.copyOf(regionBox, regionCount * 6),
        Arrays.copyOf(portalA, portalCount),
        Arrays.copyOf(portalB, portalCount),
        Arrays.copyOf(portalDirected, portalCount),
        Arrays.copyOf(portalBox, portalCount * 6));
  }

  /**
   * Keeps a mixed section's cells: a single number if the fill made it one region after all, a byte
   * per cell if its regions fit, a short per cell if not.
   */
  private void store(
      int section,
      int first,
      int from,
      int[] uniform,
      int[] base,
      byte[][] cells,
      short[][] wideCells) {
    int region = regionOf[from];
    boolean same = true;
    for (int cell = from + 1; cell < from + sectionVolume && same; cell++) {
      same = regionOf[cell] == region;
    }
    if (same) {
      uniform[section] = region;
      return;
    }
    base[section] = first;
    if (regionCount - first < RegionColumn.NARROW_NONE) {
      byte[] narrow = new byte[sectionVolume];
      for (int i = 0; i < sectionVolume; i++) {
        int r = regionOf[from + i];
        narrow[i] = (byte) (r == RegionColumn.NONE ? RegionColumn.NARROW_NONE : r - first);
      }
      cells[section] = narrow;
    } else {
      short[] wide = new short[sectionVolume];
      for (int i = 0; i < sectionVolume; i++) {
        int r = regionOf[from + i];
        wide[i] = (short) (r == RegionColumn.NONE ? RegionColumn.WIDE_NONE : r - first);
      }
      wideCells[section] = wide;
    }
  }

  /** Makes a uniform section one region, without filling it. */
  private void whole(int section, int ox, int oz) {
    int id = newRegion(sectionClass[section]);
    int from = section * sectionVolume;
    Arrays.fill(regionOf, from, from + sectionVolume, id);
    int y = minY + (section << shift);
    int[] box = {ox, y, oz, ox + mask, y + mask, oz + mask};
    System.arraycopy(box, 0, regionBox, id * 6, 6);
  }

  /** Notes which sections hold one class edge to edge, which need no fill. */
  private void uniformSections() {
    for (int s = 0; s < sections; s++) {
      int from = s * sectionVolume;
      byte first = cls[from];
      byte uniform = first;
      for (int cell = from + 1; cell < from + sectionVolume; cell++) {
        if (cls[cell] != first) {
          uniform = MIXED;
          break;
        }
      }
      sectionClass[s] = uniform;
    }
  }

  /**
   * Flood-fills one region from a seed, within its section.
   *
   * <p>Breadth-first, so the first time a cell is reached is by its shortest walk from the seed,
   * and that is the walk the detour cap is measured on.
   */
  private void fill(int seed, int section, int ox, int oz) {
    int c = family[seed];
    boolean standing = terrain.standing(c);
    boolean capped = terrain.convex(c);
    int id = newRegion(c);
    Arrays.fill(counts, 0);
    int sx = seed & mask;
    int sz = (seed >> shift) & mask;
    int sy = seed >> (2 * shift);
    int floor = section << shift;
    int ceiling = floor + mask;

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
      RegionContacts.grow(box, ox + x, minY + y, oz + z);
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
          if (y < ceiling) {
            tail = offer(nx, y + 1, nz, c, id, walked, tail, sx, sy, sz, capped);
          }
          if (y > floor) {
            tail = offer(nx, y - 1, nz, c, id, walked, tail, sx, sy, sz, capped);
          }
        }
      }
      if (y < ceiling) {
        tail = offer(x, y + 1, z, c, id, walked, tail, sx, sy, sz, capped);
      }
      if (y > floor) {
        tail = offer(x, y - 1, z, c, id, walked, tail, sx, sy, sz, capped);
      }
    }
    System.arraycopy(box, 0, regionBox, id * 6, 6);
    regionClass[id] = (byte) terrain.label(c, counts);
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
    if (regionOf[cell] != RegionColumn.NONE || family[cell] != c) {
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

  /** Finds every contact between two regions inside the column just filled. */
  private void columnContacts(int ox, int oz) {
    int volume = cls.length;
    int layer = 2 * shift;
    for (int cell = 0; cell < volume; cell++) {
      if (sectionClass[cell / sectionVolume] != MIXED && ((cell >> layer) & mask) != mask) {
        // Inside a uniform section every neighbour is the same region, and no standing cell can
        // be one: only its top layer, against the section above, can touch anything else. The
        // loop's increment lands on the top layer's first cell.
        cell = (cell - cell % sectionVolume) + sectionVolume - area - 1;
        continue;
      }
      int r = regionOf[cell];
      if (r == RegionColumn.NONE) {
        continue;
      }
      int x = cell & mask;
      int z = (cell >> shift) & mask;
      int ly = cell >> layer;
      int y = minY + ly;
      // Each face once, in the positive direction.
      if (x < mask) {
        contacts.touch(r, regionOf[cell + 1], ox + x, y, oz + z, ox + x + 1, y, oz + z);
      }
      if (z < mask) {
        contacts.touch(r, regionOf[cell + width], ox + x, y, oz + z, ox + x, y, oz + z + 1);
      }
      if (cell + area < volume) {
        contacts.touch(r, regionOf[cell + area], ox + x, y, oz + z, ox + x, y + 1, oz + z);
      }
      if (!terrain.standing(cls[cell])) {
        continue;
      }
      for (int[] h : HORIZONTAL) {
        int nx = x + h[0];
        int nz = z + h[1];
        if (nx < 0 || nx > mask || nz < 0 || nz > mask) {
          continue; // across a seam; the graph finds it
        }
        int beside = (ly << layer) | (nz << shift) | nx;
        // Steps: a standing cell joins whatever it can walk into a level above or below.
        if (beside + area < volume) {
          step(r, beside + area, ox + x, y, oz + z, ox + nx, y + 1, oz + nz);
        }
        if (beside >= area) {
          step(r, beside - area, ox + x, y, oz + z, ox + nx, y - 1, oz + nz);
        }
        if (cls[beside] >= 0 && terrain.open(cls[beside])) {
          dropInto(r, ox + x, y, oz + z, ox + nx, oz + nz, beside);
        }
      }
    }
  }

  /** A walking step from a standing cell into the cell {@code to}, a level above or below. */
  private void step(int from, int to, int fx, int fy, int fz, int tx, int ty, int tz) {
    int region = regionOf[to];
    if (region == RegionColumn.NONE || region == from || cls[to] < 0) {
      return;
    }
    if (terrain.steppable(cls[to])) {
      contacts.touch(from, region, fx, fy, fz, tx, ty, tz);
    }
  }

  /**
   * Finds where a body stepping off a ledge into {@code air} lands, and joins the ledge to it.
   *
   * <p>Separate from the fill because a fall is one-way: the ledge reaches the ground, and the
   * ground does not reach the ledge.
   */
  private void dropInto(int from, int x, int y, int z, int airX, int airZ, int air) {
    for (int k = 1; k <= terrain.deepestFall() && air - k * area >= 0; k++) {
      int below = air - k * area;
      int c = cls[below];
      if (c >= 0 && terrain.open(c)) {
        continue;
      }
      if (c >= 0 && terrain.landing(c, k)) {
        contacts.drop(from, regionOf[below], x, y, z, airX, y, airZ);
      }
      return;
    }
  }

  private int newRegion(int klass) {
    if (regionCount == regionClass.length) {
      int size = regionCount * 2;
      regionClass = Arrays.copyOf(regionClass, size);
      regionBox = Arrays.copyOf(regionBox, size * 6);
    }
    int id = regionCount++;
    regionClass[id] = (byte) klass;
    return id;
  }

  private void newPortal(int a, int b, boolean directed, int[] box) {
    if (portalCount == portalA.length) {
      int size = portalCount * 2;
      portalA = Arrays.copyOf(portalA, size);
      portalB = Arrays.copyOf(portalB, size);
      portalDirected = Arrays.copyOf(portalDirected, size);
      portalBox = Arrays.copyOf(portalBox, size * 6);
    }
    int id = portalCount++;
    portalA[id] = a;
    portalB[id] = b;
    portalDirected[id] = directed;
    System.arraycopy(box, 0, portalBox, id * 6, 6);
  }
}
