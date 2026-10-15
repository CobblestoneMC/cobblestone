/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.lod;

import java.util.Arrays;
import org.cobblestonemc.minecraft.MinecraftBlock;
import org.cobblestonemc.minecraft.MinecraftChunk;
import org.jetbrains.annotations.Nullable;

/**
 * Turns one chunk into a {@link RegionColumn}: classify every cell, flood-fill each section into
 * regions, and record where regions inside the column touch.
 *
 * <p><b>Three passes.</b>
 *
 * <ol>
 *   <li><b>Classify</b> gives every cell one {@link RegionClass} from its block, the block under it
 *       (footing) and the block over it (headroom).
 *   <li><b>Fill</b> groups touching cells of one class into regions, section by section, refusing a
 *       cell whose walk from the region's seed is much longer than the straight line ({@link
 *       RegionGraph#DEFAULT_DETOUR_RATIO}), because a region is priced in straight lines.
 *   <li><b>Contacts</b> scans for neighbouring cells in different regions — and for walking steps a
 *       level up or down, and ledges a body can drop from — and turns each touching pair into a
 *       portal whose box covers every cell where they meet.
 * </ol>
 *
 * <p><b>Change the rules, bump {@link #CLASSIFIER_VERSION}.</b> Columns are kept on disk, and a
 * stored column built under different rules is a different graph: read back silently, it would mix
 * two models in one search.
 *
 * <p>Not thread-safe: it reuses a column's worth of scratch between builds. One per thread.
 */
public final class RegionColumnBuilder {

  /**
   * The version of the rules this builds by: what each class is, how the fill splits regions, and
   * which contacts become portals. Stored columns built by any other version are rebuilt.
   */
  public static final int CLASSIFIER_VERSION = 1;

  /**
   * Blocks of detour allowed regardless of distance, so a seed's immediate neighbours never split.
   */
  private static final double DETOUR_SLACK = 3.0;

  /**
   * The deepest drop onto ground a fall portal joins.
   *
   * <p>{@code MovementCosts.SAFE_FALL_BLOCKS}. FallMode will go further at the price of damage,
   * which it charges at several seconds a block; a route that needs that is rare, and pricing it as
   * a cheap drop would invite the fine search to every cliff edge.
   */
  static final int MAX_DROP = 3;

  /** The deepest drop into water a fall portal joins: water breaks any fall. */
  static final int MAX_WATER_DROP = 32;

  /** Regions a section can hold, as an unsigned byte with {@code 0xFF} reserved for none. */
  private static final int MAX_SECTION_REGIONS = 255;

  /** A section whose cells are not all one class. */
  private static final byte MIXED = -2;

  private static final int[][] HORIZONTAL = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

  private final int worldMinY;
  private final int worldMaxY;
  private final double detourRatio;
  private final int minY;
  private final int height;
  private final int sections;

  // --- scratch, reused between builds ---
  private final MinecraftBlock[] blocks;
  private final byte[] cls;
  private final float[] breakTime;
  private final byte[] sectionClass;
  private final int[] regionOf;
  private final int[] queue = new int[16 * 16 * 16];
  private final int[] steps = new int[16 * 16 * 16];
  private final RegionContacts contacts = new RegionContacts();

  // --- the column being built ---
  private int regionCount;
  private byte[] regionClass = new byte[256];
  private int[] regionBox = new int[256 * 6];
  private float[] regionBreak = new float[256];
  private boolean[] regionNavigable = new boolean[256];
  private int portalCount;
  private int[] portalA = new int[256];
  private int[] portalB = new int[256];
  private boolean[] portalDirected = new boolean[256];
  private int[] portalBox = new int[256 * 6];

  /**
   * Returns the lowest Y a column counts its cells from: the world's floor, rounded down to a
   * section.
   *
   * @param worldMinY the world's lowest Y, inclusive
   * @return the column floor
   */
  public static int columnMinY(int worldMinY) {
    return Math.floorDiv(worldMinY, 16) * 16;
  }

  /**
   * Returns how many sections tall a column is.
   *
   * @param worldMinY the world's lowest Y, inclusive
   * @param worldMaxY the world's highest Y, inclusive
   * @return the section count
   */
  public static int sections(int worldMinY, int worldMaxY) {
    return Math.floorDiv(worldMaxY, 16) - Math.floorDiv(worldMinY, 16) + 1;
  }

  /**
   * Creates a builder for a world of the given height.
   *
   * @param worldMinY the world's lowest Y, inclusive
   * @param worldMaxY the world's highest Y, inclusive
   * @param detourRatio how much longer than a straight line a walk inside one region may be
   */
  public RegionColumnBuilder(int worldMinY, int worldMaxY, double detourRatio) {
    this.worldMinY = worldMinY;
    this.worldMaxY = worldMaxY;
    this.detourRatio = detourRatio;
    int minSection = Math.floorDiv(worldMinY, 16);
    this.sections = Math.floorDiv(worldMaxY, 16) - minSection + 1;
    // Whole sections, so a section never straddles the world's floor or ceiling; the cells outside
    // the build range are classified as nothing.
    this.minY = minSection * 16;
    this.height = sections * 16;
    int volume = height * 256;
    this.blocks = new MinecraftBlock[volume];
    this.cls = new byte[volume];
    this.breakTime = new float[volume];
    this.regionOf = new int[volume];
    this.sectionClass = new byte[sections];
  }

  /**
   * Returns the lowest Y a column counts its cells from: the world's floor, rounded down to a
   * section.
   *
   * @return the column floor
   */
  public int minY() {
    return minY;
  }

  /**
   * Returns how many sections tall a column is.
   *
   * @return the section count
   */
  public int sections() {
    return sections;
  }

  /**
   * Builds one column.
   *
   * @param chunkX the chunk X
   * @param chunkZ the chunk Z
   * @param chunk the chunk, or {@code null} if it cannot be read
   * @return the column
   */
  public RegionColumn build(int chunkX, int chunkZ, @Nullable MinecraftChunk chunk) {
    if (chunk == null) {
      // A column that cannot be read is nothing at all: no regions, so no portals into it. The
      // search routes around it, as the fine search will have to.
      return RegionColumn.empty(chunkX, chunkZ, minY, sections);
    }
    for (int ly = 0; ly < height; ly++) {
      int y = minY + ly;
      boolean inside = y >= worldMinY && y <= worldMaxY;
      for (int z = 0; z < 16; z++) {
        for (int x = 0; x < 16; x++) {
          blocks[(ly << 8) | (z << 4) | x] = inside ? chunk.block(x, y, z) : null;
        }
      }
    }
    classify();
    regionCount = 0;
    portalCount = 0;
    Arrays.fill(regionOf, RegionColumn.NONE);
    int[] uniform = new int[sections];
    int[] base = new int[sections];
    byte[][] cells = new byte[sections][];
    for (int s = 0; s < sections; s++) {
      int first = regionCount;
      int from = s << 12;
      if (sectionClass[s] != MIXED) {
        // Solid stone, open sky, deep water: one class edge to edge. A full cube is convex and
        // connected, so the fill would make it one region anyway; this skips the fill.
        uniform[s] = RegionColumn.NONE;
        if (sectionClass[s] >= 0) {
          whole(s, chunkX, chunkZ);
          uniform[s] = first;
        }
        continue;
      }
      for (int cell = from; cell < from + 4096; cell++) {
        if (regionOf[cell] == RegionColumn.NONE
            && cls[cell] >= 0
            && regionCount - first < MAX_SECTION_REGIONS) {
          fill(cell, s, chunkX, chunkZ);
        }
      }
      store(s, first, from, uniform, base, cells);
    }
    columnContacts(chunkX, chunkZ);
    contacts.flush(this::newPortal);
    return new RegionColumn(
        chunkX,
        chunkZ,
        minY,
        uniform,
        base,
        cells,
        Arrays.copyOf(regionClass, regionCount),
        Arrays.copyOf(regionBox, regionCount * 6),
        Arrays.copyOf(regionBreak, regionCount),
        Arrays.copyOf(regionNavigable, regionCount),
        Arrays.copyOf(portalA, portalCount),
        Arrays.copyOf(portalB, portalCount),
        Arrays.copyOf(portalDirected, portalCount),
        Arrays.copyOf(portalBox, portalCount * 6));
  }

  /** Keeps a mixed section's cells, or a single number if the fill made it one region after all. */
  private void store(int section, int first, int from, int[] uniform, int[] base, byte[][] cells) {
    int region = regionOf[from];
    boolean same = true;
    for (int cell = from + 1; cell < from + 4096 && same; cell++) {
      same = regionOf[cell] == region;
    }
    if (same) {
      uniform[section] = region;
      return;
    }
    byte[] local = new byte[4096];
    for (int i = 0; i < 4096; i++) {
      int r = regionOf[from + i];
      local[i] = (byte) (r == RegionColumn.NONE ? 0xFF : r - first);
    }
    base[section] = first;
    cells[section] = local;
  }

  /** Makes a uniform section one region, without filling it. */
  private void whole(int section, int chunkX, int chunkZ) {
    RegionClass klass = RegionClass.ALL[sectionClass[section]];
    int id = newRegion(klass);
    int from = section << 12;
    Arrays.fill(regionOf, from, from + 4096, id);
    int y = minY + (section << 4);
    int[] box = {chunkX * 16, y, chunkZ * 16, chunkX * 16 + 15, y + 15, chunkZ * 16 + 15};
    System.arraycopy(box, 0, regionBox, id * 6, 6);
    double breakSum = 0;
    for (int cell = from; cell < from + 4096; cell++) {
      breakSum += breakTime[cell];
    }
    regionBreak[id] = (float) (breakSum / 4096);
    if (klass == RegionClass.WATER) {
      // A surface only if something other than water sits on its top layer.
      int above = from + 4096;
      boolean navigable = above >= cls.length;
      for (int i = 0; i < 256 && !navigable; i++) {
        navigable = cls[above + i] != RegionClass.WATER.ordinal();
      }
      regionNavigable[id] = navigable;
    }
  }

  /** Sorts every cell of the column scratch into a class, or {@code -1} for none. */
  private void classify() {
    int volume = height * 256;
    for (int cell = 0; cell < volume; cell++) {
      MinecraftBlock block = blocks[cell];
      cls[cell] = -1;
      breakTime[cell] = 0;
      if (block == null) {
        continue;
      }
      if (block.isWater()) {
        cls[cell] = (byte) RegionClass.WATER.ordinal();
      } else if (block.isClimbable() || block.isScaffolding()) {
        cls[cell] = (byte) RegionClass.CLIMB.ordinal();
      } else if (block.isPassable()) {
        MinecraftBlock floor = cell >= 256 ? blocks[cell - 256] : null;
        MinecraftBlock head = cell + 256 < volume ? blocks[cell + 256] : null;
        boolean stands = floor != null && floor.isSolidTop() && (head == null || head.isPassable());
        cls[cell] =
            (byte)
                (stands
                    ? (floor.isSoulSand() ? RegionClass.SOUL : RegionClass.STAND).ordinal()
                    : RegionClass.AIR.ordinal());
      } else if (!block.isLava()) {
        double seconds = block.breakTimeSeconds();
        if (!Double.isInfinite(seconds)) {
          cls[cell] = (byte) RegionClass.SOLID.ordinal();
          breakTime[cell] = (float) seconds;
        }
        // Unbreakable: bedrock, barriers. Nothing, not a region a miner could be priced through.
      }
    }
    for (int s = 0; s < sections; s++) {
      int from = s << 12;
      byte first = cls[from];
      byte uniform = first;
      for (int cell = from + 1; cell < from + 4096; cell++) {
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
  private void fill(int seed, int section, int chunkX, int chunkZ) {
    int c = cls[seed];
    RegionClass klass = RegionClass.ALL[c];
    boolean standing = klass.standing();
    // Rock is dug through in straight lines whatever its shape, so its regions need not be convex.
    boolean capped = klass != RegionClass.SOLID;
    int id = newRegion(klass);
    int sx = seed & 15;
    int sz = (seed >> 4) & 15;
    int sy = seed >> 8;
    int floor = section << 4;
    int ceiling = floor + 15;

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
    double breakSum = 0;
    boolean navigable = false;
    while (head < tail) {
      int cell = queue[head];
      int walked = steps[head++];
      int x = cell & 15;
      int z = (cell >> 4) & 15;
      int y = cell >> 8;
      RegionContacts.grow(box, chunkX * 16 + x, minY + y, chunkZ * 16 + z);
      breakSum += breakTime[cell];
      if (klass == RegionClass.WATER && !navigable) {
        // A surface: water with something other than water above it.
        navigable = cell + 256 >= cls.length || cls[cell + 256] != RegionClass.WATER.ordinal();
      }
      for (int[] h : HORIZONTAL) {
        int nx = x + h[0];
        int nz = z + h[1];
        if (nx < 0 || nx > 15 || nz < 0 || nz > 15) {
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
    regionBreak[id] = (float) (breakSum / tail);
    regionNavigable[id] = navigable;
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
    int cell = (y << 8) | (z << 4) | x;
    if (regionOf[cell] != RegionColumn.NONE || cls[cell] != c) {
      return tail;
    }
    if (capped) {
      // Manhattan, because the fill walks face to face and so counts Manhattan steps. Against a
      // Euclidean straight line, every open cube failed the cap towards its far corners — 45 steps
      // against 26 blocks — and open sky and water split into regions for no reason. Measured in
      // the same metric as the walk, a convex patch never splits and a cave that bends back does.
      int straight = Math.abs(x - sx) + Math.abs(y - sy) + Math.abs(z - sz);
      if (walked + 1 > detourRatio * straight + DETOUR_SLACK) {
        return tail; // left for a later seed, which will start a region of its own
      }
    }
    regionOf[cell] = id;
    queue[tail] = cell;
    steps[tail] = walked + 1;
    return tail + 1;
  }

  /** Finds every contact between two regions inside the column just filled. */
  private void columnContacts(int chunkX, int chunkZ) {
    int volume = height * 256;
    int ox = chunkX * 16;
    int oz = chunkZ * 16;
    for (int cell = 0; cell < volume; cell++) {
      if (sectionClass[cell >> 12] != MIXED && ((cell >> 8) & 15) != 15) {
        // Inside a uniform section every neighbour is the same region, and no standing cell can
        // be one: only its top layer, against the section above, can touch anything else.
        cell = (cell & ~0xFFF) | 0xEFF; // the loop's increment lands on the top layer's first cell
        continue;
      }
      int r = regionOf[cell];
      if (r == RegionColumn.NONE) {
        continue;
      }
      int x = cell & 15;
      int z = (cell >> 4) & 15;
      int ly = cell >> 8;
      int y = minY + ly;
      // Each face once, in the positive direction.
      if (x < 15) {
        contacts.touch(r, regionOf[cell + 1], ox + x, y, oz + z, ox + x + 1, y, oz + z);
      }
      if (z < 15) {
        contacts.touch(r, regionOf[cell + 16], ox + x, y, oz + z, ox + x, y, oz + z + 1);
      }
      if (cell + 256 < volume) {
        contacts.touch(r, regionOf[cell + 256], ox + x, y, oz + z, ox + x, y + 1, oz + z);
      }
      if (!RegionClass.ALL[cls[cell]].standing()) {
        continue;
      }
      for (int[] h : HORIZONTAL) {
        int nx = x + h[0];
        int nz = z + h[1];
        if (nx < 0 || nx > 15 || nz < 0 || nz > 15) {
          continue; // across a seam; the graph finds it
        }
        int beside = (ly << 8) | (nz << 4) | nx;
        // Steps: a standing cell joins whatever it can walk into a level above or below.
        if (ly + 1 < height) {
          step(r, beside + 256, ox + x, y, oz + z, ox + nx, y + 1, oz + nz);
        }
        if (ly > 0) {
          step(r, beside - 256, ox + x, y, oz + z, ox + nx, y - 1, oz + nz);
        }
        if (cls[beside] == RegionClass.AIR.ordinal()) {
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
    if (steppable(RegionClass.ALL[cls[to]])) {
      contacts.touch(from, region, fx, fy, fz, tx, ty, tz);
    }
  }

  /** Whether a walking body can step into a cell of this class. */
  static boolean steppable(RegionClass klass) {
    return klass.standing() || klass == RegionClass.WATER || klass == RegionClass.CLIMB;
  }

  /**
   * Finds where a body stepping off a ledge into {@code air} lands, and joins the ledge to it.
   *
   * <p>Separate from the fill because a fall is one-way: the ledge reaches the ground, and the
   * ground does not reach the ledge.
   */
  private void dropInto(int from, int x, int y, int z, int airX, int airZ, int air) {
    for (int k = 1; k <= MAX_WATER_DROP && air - k * 256 >= 0; k++) {
      int below = air - k * 256;
      int c = cls[below];
      if (c == RegionClass.AIR.ordinal()) {
        continue;
      }
      if (c >= 0 && landing(RegionClass.ALL[c], k)) {
        contacts.drop(from, regionOf[below], x, y, z, airX, y, airZ);
      }
      return;
    }
  }

  /** Whether a fall of {@code depth} blocks onto this class is one worth a portal. */
  static boolean landing(RegionClass klass, int depth) {
    // One block down is a step, already joined by the step contacts.
    return (klass.standing() && depth >= 2 && depth <= MAX_DROP)
        || klass == RegionClass.WATER
        || (klass == RegionClass.CLIMB && depth <= MAX_DROP);
  }

  private int newRegion(RegionClass klass) {
    if (regionCount == regionClass.length) {
      int size = regionCount * 2;
      regionClass = Arrays.copyOf(regionClass, size);
      regionBox = Arrays.copyOf(regionBox, size * 6);
      regionBreak = Arrays.copyOf(regionBreak, size);
      regionNavigable = Arrays.copyOf(regionNavigable, size);
    }
    int id = regionCount++;
    regionClass[id] = (byte) klass.ordinal();
    regionBreak[id] = 0;
    regionNavigable[id] = false;
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
