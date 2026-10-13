/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.lod;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.cobblestonemc.FutureOr;
import org.cobblestonemc.minecraft.MinecraftBlock;
import org.cobblestonemc.minecraft.MinecraftChunk;
import org.jetbrains.annotations.Nullable;

/**
 * The world as a graph of terrain regions joined by portals.
 *
 * <p><b>Exact about topology, loose about geometry.</b> Every region is a flood-filled connected
 * piece of one {@link RegionClass}, so two regions are joined only where a body could actually
 * cross between them; but nothing inside a region is remembered beyond its bounding box, and the
 * cost of crossing one is a straight line priced at its class's rate. That is the trade this layer
 * is built on: a wrong price costs the fine search a few nodes, while a wrong connection — a wall
 * reported as a doorway — costs it thousands, so connections are computed from blocks and prices
 * are allowed to be rough. It is what the slice-coverage profile could not do: a coverage fraction
 * cannot tell whether the open half of a section reaches the other side.
 *
 * <p><b>Built per chunk column, joined at seams.</b> A column is read once and every section of it
 * is classified and filled together, so the vertical joins between its sections, and the falls from
 * one to another, are exact. Neighbouring columns are joined by a seam the first time a search
 * needs to leave one, which needs both columns in hand. A column whose four seams are built is
 * <em>complete</em>: its regions will never gain another portal, which is what lets a search relax
 * across a region once and trust that it saw everything.
 *
 * <p><b>Regions stay inside a section, and stay nearly convex.</b> Within a section the fill
 * refuses a cell whose walking distance from the region's seed is much longer than the straight
 * line ({@link #DEFAULT_DETOUR_RATIO}), because a region is priced in straight lines and a U-shaped
 * cave priced that way would claim a shortcut through its own wall. Large structures — a plain, a
 * river — become chains of section-sized regions, and the portals between them carry the shape.
 *
 * <p><b>Agent-independent.</b> Nothing here knows who is travelling: a region says what the terrain
 * is, and {@link RegionCost} decides what that is worth and whether it is passable at all. That is
 * what would let a server share one graph between every search over the same terrain; today each
 * solve builds its own.
 *
 * <p>Not thread-safe. One solve owns one graph, and the search that reads it is single-flight.
 */
public final class RegionGraph {

  /** No region: unbreakable, lava, outside the world, or a chunk that cannot be read. */
  public static final int NONE = -1;

  /**
   * How much longer than a straight line the walk from a region's seed to one of its cells may be,
   * by default.
   *
   * <p>The region is priced as a straight line, so this is the most it can under-price a crossing
   * by: half again, plus {@link #DETOUR_SLACK}. A cave bending back on itself splits into two
   * regions joined at the bend, and the bend is then where the route is priced through.
   */
  public static final double DEFAULT_DETOUR_RATIO = 1.5;

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
  private static final int MAX_DROP = 3;

  /** The deepest drop into water a fall portal joins: water breaks any fall. */
  private static final int MAX_WATER_DROP = 32;

  /** Regions a section can hold, as an unsigned byte with {@code 0xFF} reserved for none. */
  private static final int MAX_SECTION_REGIONS = 255;

  private static final int[][] HORIZONTAL = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

  private final ChunkColumns source;
  private final double detourRatio;
  private final int minY;
  private final int height;
  private final int sections;
  private final int minSection;

  private final Map<Long, Column> columns = new HashMap<>();
  private final Set<Long> seams = new HashSet<>();

  // --- regions, struct of arrays ---
  private int regionCount;
  private byte[] regionClass = new byte[1024];
  private int[] regionBox = new int[6 * 1024];
  private float[] regionBreak = new float[1024];
  private boolean[] regionNavigable = new boolean[1024];
  private long[] regionColumn = new long[1024];
  private int[][] regionPortals = new int[1024][];
  private int[] regionPortalCount = new int[1024];

  // --- portals ---
  private int portalCount;
  private int[] portalA = new int[1024];
  private int[] portalB = new int[1024];
  private boolean[] portalDirected = new boolean[1024];
  private int[] portalBox = new int[6 * 1024];

  // --- scratch for building one column, reused ---
  private final MinecraftBlock[] blocks;
  private final byte[] cls;
  private final float[] breakTime;
  private final int[] regionOf;
  private final int[] queue = new int[16 * 16 * 16];
  private final int[] steps = new int[16 * 16 * 16];

  /** Contacts found while building a column or seam, before they become portals. */
  private final Map<Long, int[]> contacts = new HashMap<>();

  private final Map<Long, int[]> drops = new HashMap<>();

  /** The last contact touched, since consecutive cells nearly always join the same two regions. */
  private long lastContact = Long.MIN_VALUE;

  private int[] lastContactBox;

  private long buildNanos;
  private long seamNanos;

  /**
   * Creates an empty graph over a source of chunk columns.
   *
   * @param source where columns come from
   */
  public RegionGraph(ChunkColumns source) {
    this(source, DEFAULT_DETOUR_RATIO);
  }

  /**
   * Creates an empty graph that splits regions at a chosen detour.
   *
   * @param source where columns come from
   * @param detourRatio how much longer than a straight line a walk inside one region may be; see
   *     {@link #DEFAULT_DETOUR_RATIO}
   */
  public RegionGraph(ChunkColumns source, double detourRatio) {
    this.source = source;
    this.detourRatio = detourRatio;
    this.minSection = Math.floorDiv(source.minY(), 16);
    int maxSection = Math.floorDiv(source.maxY(), 16);
    this.sections = maxSection - minSection + 1;
    // Whole sections, so a section never straddles the world's floor or ceiling; the cells outside
    // the build range are classified as nothing.
    this.minY = minSection * 16;
    this.height = sections * 16;
    int volume = height * 256;
    this.blocks = new MinecraftBlock[volume];
    this.cls = new byte[volume];
    this.breakTime = new float[volume];
    this.regionOf = new int[volume];
  }

  // ------------------------------------------------------------------------------------------
  // Reading
  // ------------------------------------------------------------------------------------------

  /**
   * Returns the region at a world cell, or {@link #NONE}, if its column is built.
   *
   * @param x the world X
   * @param y the world Y
   * @param z the world Z
   * @return the region, or {@link #NONE} — including when the column has not been built
   */
  public int regionAt(int x, int y, int z) {
    Column column = columns.get(columnKey(Math.floorDiv(x, 16), Math.floorDiv(z, 16)));
    if (column == null) {
      return NONE;
    }
    return column.regionAt(x & 15, y - minY, z & 15);
  }

  /**
   * Returns a region's class.
   *
   * @param region the region
   * @return its class
   */
  public RegionClass regionClass(int region) {
    return RegionClass.ALL[regionClass[region]];
  }

  /**
   * Returns the mean break time of a {@link RegionClass#SOLID} region's blocks.
   *
   * @param region the region
   * @return seconds per block broken, or 0 for any other class
   */
  public double breakTime(int region) {
    return regionBreak[region];
  }

  /**
   * Returns whether a {@link RegionClass#WATER} region has a surface a boat floats on.
   *
   * @param region the region
   * @return {@code true} if a boat can cross it
   */
  public boolean navigable(int region) {
    return regionNavigable[region];
  }

  /**
   * Returns how many portals a region has.
   *
   * @param region the region
   * @return the portal count
   */
  public int portalCount(int region) {
    return regionPortalCount[region];
  }

  /**
   * Returns one of a region's portals.
   *
   * @param region the region
   * @param index which, from 0 to {@link #portalCount(int)}
   * @return the portal
   */
  public int portal(int region, int index) {
    return regionPortals[region][index];
  }

  /**
   * Returns the region on one side of a portal: the side an agent leaves, if it is one-way.
   *
   * @param portal the portal
   * @return the first region
   */
  public int portalA(int portal) {
    return portalA[portal];
  }

  /**
   * Returns the region on the other side of a portal: the side an agent arrives in, if one-way.
   *
   * @param portal the portal
   * @return the second region
   */
  public int portalB(int portal) {
    return portalB[portal];
  }

  /**
   * Returns whether a portal is only crossed from {@link #portalA} to {@link #portalB} — a fall.
   *
   * @param portal the portal
   * @return {@code true} if one-way
   */
  public boolean directed(int portal) {
    return portalDirected[portal];
  }

  /**
   * Returns one bound of a portal's box: the cells on both sides of the contact.
   *
   * @param portal the portal
   * @param bound 0–2 the minimum X, Y, Z and 3–5 the maximum
   * @return the bound, in world coordinates
   */
  public int portalBound(int portal, int bound) {
    return portalBox[portal * 6 + bound];
  }

  /**
   * Returns how many portals the graph holds; every portal index is below this.
   *
   * @return the portal count
   */
  public int portals() {
    return portalCount;
  }

  /**
   * Returns how many regions the graph holds.
   *
   * @return the region count
   */
  public int regions() {
    return regionCount;
  }

  /**
   * Returns how many chunk columns have been built.
   *
   * @return the column count
   */
  public int columnsBuilt() {
    return columns.size();
  }

  /**
   * Returns the time spent classifying and filling columns: reading every block of a chunk that is
   * already in hand, and turning it into regions and the portals inside the column.
   *
   * @return nanoseconds
   */
  public long buildNanos() {
    return buildNanos;
  }

  /**
   * Returns the time spent joining columns at their seams.
   *
   * @return nanoseconds
   */
  public long seamNanos() {
    return seamNanos;
  }

  /**
   * Returns roughly how much memory the graph holds, in bytes: what it would cost to keep.
   *
   * <p>An estimate from array sizes, not a measurement of the heap. Object headers and the column
   * map's own entries are counted at typical JVM sizes; the build scratch, which is the same size
   * for any graph, is left out.
   *
   * @return the approximate footprint
   */
  public long footprintBytes() {
    long bytes = 0;
    for (Column column : columns.values()) {
      bytes += 64 + column.uniform.length * 8L; // the map entry, uniform and base
      for (byte[] cells : column.local) {
        bytes += cells == null ? 8 : 16 + cells.length;
      }
    }
    // Per region: class, box, break time, navigable, column, portal list header and count.
    bytes += regionCount * (1L + 24 + 4 + 1 + 8 + 16 + 4);
    for (int region = 0; region < regionCount; region++) {
      bytes += regionPortals[region].length * 4L;
    }
    // Per portal: both regions, the flag, and the box.
    bytes += portalCount * (4L + 4 + 1 + 24);
    return bytes;
  }

  // ------------------------------------------------------------------------------------------
  // Completing
  // ------------------------------------------------------------------------------------------

  /**
   * Makes sure the column holding a world cell is built and complete, so every region in it has all
   * of its portals.
   *
   * @param x the world X
   * @param z the world Z
   * @return a future completing when the columns it needs arrive, or {@code null} if it is ready
   */
  public @Nullable CompletableFuture<Void> complete(int x, int z) {
    return completeColumn(Math.floorDiv(x, 16), Math.floorDiv(z, 16));
  }

  /**
   * Makes sure a region's column is complete.
   *
   * @param region the region
   * @return a future completing when the columns it needs arrive, or {@code null} if it is ready
   */
  public @Nullable CompletableFuture<Void> completeRegion(int region) {
    long key = regionColumn[region];
    Column column = columns.get(key);
    if (column != null && column.complete) {
      return null; // the common case, and it allocates nothing
    }
    return completeColumn(unpackX(key), unpackZ(key));
  }

  private @Nullable CompletableFuture<Void> completeColumn(int cx, int cz) {
    Column column = columns.get(columnKey(cx, cz));
    if (column != null && column.complete) {
      return null;
    }
    // Every column a seam reads, fetched together: one park for all five rather than five parks.
    List<CompletableFuture<?>> waiting = null;
    int[][] around = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    for (int[] offset : around) {
      CompletableFuture<?> pending = build(cx + offset[0], cz + offset[1]);
      if (pending != null) {
        if (waiting == null) {
          waiting = new ArrayList<>();
        }
        waiting.add(pending);
      }
    }
    if (waiting != null) {
      return CompletableFuture.allOf(waiting.toArray(new CompletableFuture[0]));
    }
    long started = System.nanoTime();
    seam(cx, cz, true);
    seam(cx - 1, cz, true);
    seam(cx, cz, false);
    seam(cx, cz - 1, false);
    seamNanos += System.nanoTime() - started;
    columns.get(columnKey(cx, cz)).complete = true;
    return null;
  }

  /** Builds a column if its chunk is in hand, or returns the future its chunk arrives on. */
  private @Nullable CompletableFuture<?> build(int cx, int cz) {
    long key = columnKey(cx, cz);
    if (columns.containsKey(key)) {
      return null;
    }
    FutureOr<MinecraftChunk> chunk = source.column(cx, cz);
    if (!chunk.isImmediate()) {
      return chunk.future();
    }
    long started = System.nanoTime();
    columns.put(key, buildColumn(cx, cz, chunk.value()));
    buildNanos += System.nanoTime() - started;
    return null;
  }

  // ------------------------------------------------------------------------------------------
  // Building a column
  // ------------------------------------------------------------------------------------------

  private Column buildColumn(int cx, int cz, @Nullable MinecraftChunk chunk) {
    Column column = new Column(sections);
    if (chunk == null) {
      // A column that cannot be read is nothing at all: no regions, so no portals into it. The
      // search routes around it, as the fine search will have to.
      Arrays.fill(column.uniform, NONE);
      return column;
    }
    int top = source.maxY();
    int bottom = source.minY();
    for (int ly = 0; ly < height; ly++) {
      int y = minY + ly;
      boolean inside = y >= bottom && y <= top;
      for (int z = 0; z < 16; z++) {
        for (int x = 0; x < 16; x++) {
          blocks[(ly << 8) | (z << 4) | x] = inside ? chunk.block(x, y, z) : null;
        }
      }
    }
    classify();
    Arrays.fill(regionOf, NONE);
    long key = columnKey(cx, cz);
    for (int s = 0; s < sections; s++) {
      int base = regionCount;
      int from = s << 12;
      for (int cell = from; cell < from + 4096; cell++) {
        if (regionOf[cell] == NONE && cls[cell] >= 0 && regionCount - base < MAX_SECTION_REGIONS) {
          fill(cell, s, cx, cz, key);
        }
      }
      column.store(s, base, regionOf, from);
    }
    columnContacts(cx, cz);
    flushContacts();
    return column;
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
  }

  /**
   * Flood-fills one region from a seed, within its section.
   *
   * <p>Breadth-first, so the first time a cell is reached is by its shortest walk from the seed,
   * and that is the walk the detour cap is measured on.
   */
  private void fill(int seed, int section, int cx, int cz, long key) {
    int c = cls[seed];
    RegionClass klass = RegionClass.ALL[c];
    boolean standing = klass.standing();
    // Rock is dug through in straight lines whatever its shape, so its regions need not be convex.
    boolean capped = klass != RegionClass.SOLID;
    int id = newRegion(klass, key);
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
      grow(box, cx * 16 + x, minY + y, cz * 16 + z);
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
        tail = offer(cell, nx, y, nz, c, id, walked, tail, sx, sy, sz, capped);
        if (standing) {
          // A step up or down is a walking move, and joins two standing cells a level apart.
          if (y < ceiling) {
            tail = offer(cell, nx, y + 1, nz, c, id, walked, tail, sx, sy, sz, capped);
          }
          if (y > floor) {
            tail = offer(cell, nx, y - 1, nz, c, id, walked, tail, sx, sy, sz, capped);
          }
        }
      }
      if (y < ceiling) {
        tail = offer(cell, x, y + 1, z, c, id, walked, tail, sx, sy, sz, capped);
      }
      if (y > floor) {
        tail = offer(cell, x, y - 1, z, c, id, walked, tail, sx, sy, sz, capped);
      }
    }
    System.arraycopy(box, 0, regionBox, id * 6, 6);
    regionBreak[id] = (float) (breakSum / tail);
    regionNavigable[id] = navigable;
  }

  private int offer(
      int from,
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
    if (regionOf[cell] != NONE || cls[cell] != c) {
      return tail;
    }
    if (capped) {
      int dx = x - sx;
      int dy = y - sy;
      int dz = z - sz;
      double straight = Math.sqrt(dx * dx + dy * dy + dz * dz);
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
  private void columnContacts(int cx, int cz) {
    int volume = height * 256;
    int ox = cx * 16;
    int oz = cz * 16;
    for (int cell = 0; cell < volume; cell++) {
      int r = regionOf[cell];
      if (r == NONE) {
        continue;
      }
      int x = cell & 15;
      int z = (cell >> 4) & 15;
      int y = cell >> 8;
      // Each face once, in the positive direction.
      if (x < 15) {
        touch(r, regionOf[cell + 1], ox + x, y, oz + z, ox + x + 1, y, oz + z);
      }
      if (z < 15) {
        touch(r, regionOf[cell + 16], ox + x, y, oz + z, ox + x, y, oz + z + 1);
      }
      if (cell + 256 < volume) {
        touch(r, regionOf[cell + 256], ox + x, y, oz + z, ox + x, y + 1, oz + z);
      }
      if (!RegionClass.ALL[cls[cell]].standing()) {
        continue;
      }
      for (int[] h : HORIZONTAL) {
        int nx = x + h[0];
        int nz = z + h[1];
        if (nx < 0 || nx > 15 || nz < 0 || nz > 15) {
          continue; // across a seam; the seam finds it
        }
        int beside = (y << 8) | (nz << 4) | nx;
        // Steps: a standing cell joins whatever it can walk into a level above or below.
        if (y + 1 < height) {
          step(r, regionOf[beside + 256], ox + x, y, oz + z, ox + nx, y + 1, oz + nz);
        }
        if (y > 0) {
          step(r, regionOf[beside - 256], ox + x, y, oz + z, ox + nx, y - 1, oz + nz);
        }
        if (cls[beside] == RegionClass.AIR.ordinal()) {
          dropInto(r, ox + x, y, oz + z, ox + nx, oz + nz, beside, cls, regionOf);
        }
      }
    }
  }

  /**
   * Finds where a body stepping off a ledge into {@code air} lands, and joins the ledge to it.
   *
   * <p>Separate from the fill because a fall is one-way: the ledge reaches the ground, and the
   * ground does not reach the ledge.
   */
  private void dropInto(
      int from, int x, int y, int z, int airX, int airZ, int air, byte[] classes, int[] regions) {
    for (int k = 1; k <= MAX_WATER_DROP && air - k * 256 >= 0; k++) {
      int below = air - k * 256;
      int c = classes[below];
      if (c == RegionClass.AIR.ordinal()) {
        continue;
      }
      boolean ground = c == RegionClass.STAND.ordinal() || c == RegionClass.SOUL.ordinal();
      // One block down is a step, already joined by the step contacts.
      if ((ground && k >= 2 && k <= MAX_DROP)
          || c == RegionClass.WATER.ordinal()
          || (c == RegionClass.CLIMB.ordinal() && k <= MAX_DROP)) {
        drop(from, regions[below], x, y, z, airX, y, airZ);
      }
      return;
    }
  }

  // ------------------------------------------------------------------------------------------
  // Seams
  // ------------------------------------------------------------------------------------------

  /**
   * Joins a column to its neighbour in positive X (or positive Z), once.
   *
   * <p>Both columns must be built. The scratch arrays hold neither, so this reads each through its
   * stored regions; the class of a cell is its region's class.
   */
  private void seam(int cx, int cz, boolean alongX) {
    long key = (columnKey(cx, cz) << 1) | (alongX ? 1 : 0);
    if (!seams.add(key)) {
      return;
    }
    Column low = columns.get(columnKey(cx, cz));
    Column high = columns.get(alongX ? columnKey(cx + 1, cz) : columnKey(cx, cz + 1));
    int ox = cx * 16;
    int oz = cz * 16;
    for (int ly = 0; ly < height; ly++) {
      for (int t = 0; t < 16; t++) {
        // a is the low column's edge cell, b the high column's, face to face.
        int ax = alongX ? 15 : t;
        int az = alongX ? t : 15;
        int bx = alongX ? 0 : t;
        int bz = alongX ? t : 0;
        int wax = ox + ax;
        int waz = oz + az;
        int wbx = alongX ? ox + 16 : ox + t;
        int wbz = alongX ? oz + t : oz + 16;
        int ra = low.regionAt(ax, ly, az);
        int rb = high.regionAt(bx, ly, bz);
        touch(ra, rb, wax, ly, waz, wbx, ly, wbz);
        if (ra != NONE && regionClassOf(ra).standing()) {
          seamSteps(ra, high, bx, ly, bz, wax, waz, wbx, wbz);
          if (rb != NONE && regionClassOf(rb) == RegionClass.AIR) {
            seamDrop(ra, high, bx, ly, bz, wax, waz, wbx, wbz);
          }
        }
        if (rb != NONE && regionClassOf(rb).standing()) {
          seamSteps(rb, low, ax, ly, az, wbx, wbz, wax, waz);
          if (ra != NONE && regionClassOf(ra) == RegionClass.AIR) {
            seamDrop(rb, low, ax, ly, az, wbx, wbz, wax, waz);
          }
        }
      }
    }
    flushContacts();
  }

  private void seamSteps(
      int from, Column other, int lx, int ly, int lz, int fx, int fz, int tx, int tz) {
    if (ly + 1 < height) {
      step(from, other.regionAt(lx, ly + 1, lz), fx, ly, fz, tx, ly + 1, tz);
    }
    if (ly > 0) {
      step(from, other.regionAt(lx, ly - 1, lz), fx, ly, fz, tx, ly - 1, tz);
    }
  }

  private void seamDrop(
      int from, Column other, int lx, int ly, int lz, int fx, int fz, int tx, int tz) {
    for (int k = 1; k <= MAX_WATER_DROP && ly - k >= 0; k++) {
      int below = other.regionAt(lx, ly - k, lz);
      if (below == NONE) {
        return;
      }
      RegionClass c = regionClassOf(below);
      if (c == RegionClass.AIR) {
        continue;
      }
      if ((c.standing() && k >= 2 && k <= MAX_DROP)
          || c == RegionClass.WATER
          || (c == RegionClass.CLIMB && k <= MAX_DROP)) {
        drop(from, below, fx, ly, fz, tx, ly, tz);
      }
      return;
    }
  }

  // ------------------------------------------------------------------------------------------
  // Contacts and portals
  // ------------------------------------------------------------------------------------------

  /** Records that two cells of different regions share a face, in column-local Y. */
  private void touch(int a, int b, int ax, int ay, int az, int bx, int by, int bz) {
    if (a == NONE || b == NONE || a == b) {
      return;
    }
    long key = a < b ? ((long) a << 32) | b : ((long) b << 32) | a;
    record(contacts, key, ax, ay, az, bx, by, bz);
  }

  /** A walking step between a standing cell and one a level above or below. */
  private void step(int from, int to, int fx, int fy, int fz, int tx, int ty, int tz) {
    if (to == NONE || to == from) {
      return;
    }
    RegionClass c = regionClassOf(to);
    if (c.standing() || c == RegionClass.WATER || c == RegionClass.CLIMB) {
      touch(from, to, fx, fy, fz, tx, ty, tz);
    }
  }

  /**
   * A fall from {@code from} into {@code to}, recorded one-way at the ledge: the box is the ledge
   * and the air beside it, and the height fallen is priced inside the region landed in.
   */
  private void drop(int from, int to, int fx, int fy, int fz, int ax, int ay, int az) {
    if (to == NONE || to == from) {
      return;
    }
    record(drops, ((long) from << 32) | to, fx, fy, fz, ax, ay, az);
  }

  private void record(
      Map<Long, int[]> into, long key, int ax, int ay, int az, int bx, int by, int bz) {
    int[] box;
    if (into == contacts && key == lastContact) {
      box = lastContactBox;
    } else {
      box = into.get(key);
      if (box == null) {
        box =
            new int[] {
              Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
              -Integer.MAX_VALUE, -Integer.MAX_VALUE, -Integer.MAX_VALUE
            };
        into.put(key, box);
      }
      if (into == contacts) {
        lastContact = key;
        lastContactBox = box;
      }
    }
    grow(box, ax, minY + ay, az);
    grow(box, bx, minY + by, bz);
  }

  private void flushContacts() {
    for (Map.Entry<Long, int[]> entry : contacts.entrySet()) {
      long key = entry.getKey();
      newPortal((int) (key >>> 32), (int) key, false, entry.getValue());
    }
    for (Map.Entry<Long, int[]> entry : drops.entrySet()) {
      long key = entry.getKey();
      newPortal((int) (key >>> 32), (int) key, true, entry.getValue());
    }
    contacts.clear();
    drops.clear();
    lastContact = Long.MIN_VALUE;
    lastContactBox = null;
  }

  private RegionClass regionClassOf(int region) {
    return RegionClass.ALL[regionClass[region]];
  }

  private int newRegion(RegionClass klass, long column) {
    if (regionCount == regionClass.length) {
      int size = regionCount * 2;
      regionClass = Arrays.copyOf(regionClass, size);
      regionBox = Arrays.copyOf(regionBox, size * 6);
      regionBreak = Arrays.copyOf(regionBreak, size);
      regionNavigable = Arrays.copyOf(regionNavigable, size);
      regionColumn = Arrays.copyOf(regionColumn, size);
      regionPortals = Arrays.copyOf(regionPortals, size);
      regionPortalCount = Arrays.copyOf(regionPortalCount, size);
    }
    int id = regionCount++;
    regionClass[id] = (byte) klass.ordinal();
    regionColumn[id] = column;
    regionPortals[id] = new int[4];
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
    attach(a, id);
    attach(b, id);
  }

  private void attach(int region, int portal) {
    int count = regionPortalCount[region];
    if (count == regionPortals[region].length) {
      regionPortals[region] = Arrays.copyOf(regionPortals[region], count * 2);
    }
    regionPortals[region][count] = portal;
    regionPortalCount[region] = count + 1;
  }

  private static void grow(int[] box, int x, int y, int z) {
    box[0] = Math.min(box[0], x);
    box[1] = Math.min(box[1], y);
    box[2] = Math.min(box[2], z);
    box[3] = Math.max(box[3], x);
    box[4] = Math.max(box[4], y);
    box[5] = Math.max(box[5], z);
  }

  private static long columnKey(int cx, int cz) {
    return ((long) cx << 32) | (cz & 0xFFFF_FFFFL);
  }

  private static int unpackX(long key) {
    return (int) (key >> 32);
  }

  private static int unpackZ(long key) {
    return (int) key;
  }

  /**
   * One built chunk column: which region every cell belongs to.
   *
   * <p>Most sections are one region from edge to edge — open sky, solid stone — and those keep a
   * single number instead of 4 096 bytes, which is most of what keeps a long route's graph small.
   */
  private static final class Column {

    final int[] uniform;
    final int[] base;
    final byte[][] local;
    boolean complete;

    Column(int sections) {
      this.uniform = new int[sections];
      this.base = new int[sections];
      this.local = new byte[sections][];
    }

    void store(int section, int base, int[] regionOf, int from) {
      int first = regionOf[from];
      boolean same = true;
      for (int cell = from + 1; cell < from + 4096 && same; cell++) {
        same = regionOf[cell] == first;
      }
      if (same) {
        uniform[section] = first;
        return;
      }
      byte[] cells = new byte[4096];
      for (int i = 0; i < 4096; i++) {
        int region = regionOf[from + i];
        cells[i] = (byte) (region == NONE ? 0xFF : region - base);
      }
      this.base[section] = base;
      local[section] = cells;
    }

    int regionAt(int lx, int ly, int lz) {
      if (ly < 0 || ly >= uniform.length * 16) {
        return NONE;
      }
      int section = ly >> 4;
      byte[] cells = local[section];
      if (cells == null) {
        return uniform[section];
      }
      int value = cells[((ly & 15) << 8) | (lz << 4) | lx] & 0xFF;
      return value == 0xFF ? NONE : base[section] + value;
    }
  }

  /**
   * Where the graph gets its terrain: one chunk column at a time.
   *
   * <p>⚠️ <b>An implementation must remember what it fetched.</b> Once a returned future has
   * completed, a later call for the same column has to answer immediately, or the search parks on
   * the same column forever.
   */
  public interface ChunkColumns {

    /**
     * Returns a chunk column, or an immediate {@code null} if it cannot be known.
     *
     * @param chunkX the chunk X
     * @param chunkZ the chunk Z
     * @return the chunk
     */
    FutureOr<MinecraftChunk> column(int chunkX, int chunkZ);

    /**
     * Returns the lowest world Y, inclusive.
     *
     * @return the floor
     */
    int minY();

    /**
     * Returns the highest world Y, inclusive.
     *
     * @return the ceiling
     */
    int maxY();
  }
}
