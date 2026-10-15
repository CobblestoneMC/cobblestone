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
import java.util.concurrent.ConcurrentHashMap;
import org.cobblestonemc.FutureOr;
import org.cobblestonemc.minecraft.MinecraftChunk;
import org.jetbrains.annotations.Nullable;

/**
 * The world as a graph of terrain regions joined by portals, as one search sees it.
 *
 * <p><b>Exact about topology, loose about geometry.</b> Every region is a flood-filled connected
 * piece of one {@link RegionClass}, so two regions are joined only where a body could actually
 * cross between them; but nothing inside a region is remembered beyond its bounding box, and the
 * cost of crossing one is a straight line priced at its class's rate. That is the trade this layer
 * is built on: a wrong price costs the fine search a few nodes, while a wrong connection — a wall
 * reported as a doorway — costs it thousands, so connections are computed from blocks and prices
 * are allowed to be rough.
 *
 * <p><b>Columns come from a {@link RegionStore}; seams are built here.</b> A column — every region
 * and portal inside one chunk — is built once and shared by every search through the store, and
 * kept on disk across restarts. This graph places each column it needs at an offset of its own, and
 * joins neighbouring columns at seams the first time the search needs to leave one: seams need both
 * columns, and are cheap enough to compute per search. A column whose four seams are built is
 * <em>complete</em>: its regions will never gain another portal, which is what lets a search relax
 * across a region once and trust that it saw everything.
 *
 * <p><b>Agent-independent.</b> Nothing here knows who is travelling: a region says what the terrain
 * is, and {@link RegionCost} decides what that is worth and whether it is passable at all.
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
   * by: half again, plus a few blocks of slack. A cave bending back on itself splits into two
   * regions joined at the bend, and the bend is then where the route is priced through.
   */
  public static final double DEFAULT_DETOUR_RATIO = 1.5;

  private final RegionStore store;
  private final ChunkColumns chunks;
  private final int minY;
  private final int height;

  private final Map<Long, Placed> columns = new HashMap<>();
  private final Set<Long> seams = new HashSet<>();

  /**
   * Columns the store handed over after this graph had to wait for them, by column.
   *
   * <p>Kept here rather than asked for again: the store has them in memory too, but under a tight
   * budget it may already have evicted one, and a search asking again would wait again.
   */
  private final Map<Long, RegionColumn> arrived = new ConcurrentHashMap<>();

  private final RegionContacts contacts = new RegionContacts();

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

  private long placeNanos;
  private long seamNanos;

  /**
   * Creates a graph over a store of its own, keeping everything in memory: one search's worth.
   *
   * @param chunks where columns come from
   */
  public RegionGraph(ChunkColumns chunks) {
    this(chunks, DEFAULT_DETOUR_RATIO);
  }

  /**
   * Creates a graph over a store of its own that splits regions at a chosen detour.
   *
   * @param chunks where columns come from
   * @param detourRatio how much longer than a straight line a walk inside one region may be; see
   *     {@link #DEFAULT_DETOUR_RATIO}
   */
  public RegionGraph(ChunkColumns chunks, double detourRatio) {
    this(RegionStore.inMemory(chunks.minY(), chunks.maxY(), detourRatio), chunks);
  }

  /**
   * Creates a graph over a shared store.
   *
   * @param store where built columns are kept
   * @param chunks where this search reads a chunk the store has to build a column from
   */
  public RegionGraph(RegionStore store, ChunkColumns chunks) {
    this.store = store;
    this.chunks = chunks;
    this.minY = store.columnMinY();
    this.height = store.sections() * 16;
  }

  // ------------------------------------------------------------------------------------------
  // Reading
  // ------------------------------------------------------------------------------------------

  /**
   * Returns the region at a world cell, or {@link #NONE}, if its column is placed.
   *
   * @param x the world X
   * @param y the world Y
   * @param z the world Z
   * @return the region, or {@link #NONE} — including when the column has not been placed
   */
  public int regionAt(int x, int y, int z) {
    Placed column = columns.get(columnKey(Math.floorDiv(x, 16), Math.floorDiv(z, 16)));
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
   * Returns one bound of a region's box.
   *
   * @param region the region
   * @param bound 0–2 the minimum X, Y, Z and 3–5 the maximum
   * @return the bound, in world coordinates
   */
  public int regionBound(int region, int bound) {
    return regionBox[region * 6 + bound];
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
   * Returns how many chunk columns have been placed.
   *
   * @return the column count
   */
  public int columnsBuilt() {
    return columns.size();
  }

  /**
   * Returns the store this graph reads columns from.
   *
   * @return the store
   */
  public RegionStore store() {
    return store;
  }

  /**
   * Returns the time spent placing stored columns into this graph: copying their regions and
   * portals in at this graph's numbering. Building a column is the store's, and counted there.
   *
   * @return nanoseconds
   */
  public long placeNanos() {
    return placeNanos;
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
   * Returns roughly how much memory this graph holds of its own, in bytes.
   *
   * <p>Its regions and portals, which it copies; not the columns' cell maps, which it shares with
   * the store.
   *
   * @return the approximate footprint
   */
  public long footprintBytes() {
    long bytes = columns.size() * 64L;
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
   * Makes sure the column holding a world cell is placed and complete, so every region in it has
   * all of its portals.
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
    Placed column = columns.get(key);
    if (column != null && column.complete) {
      return null; // the common case, and it allocates nothing
    }
    return completeColumn(unpackX(key), unpackZ(key));
  }

  private @Nullable CompletableFuture<Void> completeColumn(int cx, int cz) {
    Placed column = columns.get(columnKey(cx, cz));
    if (column != null && column.complete) {
      return null;
    }
    // Every column a seam reads, asked for together: one park for all five rather than five.
    List<CompletableFuture<?>> waiting = null;
    int[][] around = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    for (int[] offset : around) {
      CompletableFuture<?> pending = place(cx + offset[0], cz + offset[1]);
      if (pending != null) {
        if (waiting == null) {
          waiting = new ArrayList<>();
        }
        waiting.add(pending);
      }
    }
    if (waiting != null) {
      return CompletableFuture.allOf(waiting.toArray(new CompletableFuture<?>[0]));
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

  /** Places a column if the store has it ready, or returns the future it arrives on. */
  private @Nullable CompletableFuture<?> place(int cx, int cz) {
    long key = columnKey(cx, cz);
    if (columns.containsKey(key)) {
      return null;
    }
    RegionColumn ready = arrived.remove(key);
    if (ready == null) {
      FutureOr<RegionColumn> column = store.column(cx, cz, chunks);
      if (!column.isImmediate()) {
        // Kept as it lands, so the search finds it on waking whatever the store does meanwhile. A
        // failure is a column that cannot be known, like an ungenerated one.
        return column
            .future()
            .handle(
                (built, failure) -> {
                  arrived.put(
                      key, built != null ? built : RegionColumn.empty(cx, cz, minY, height / 16));
                  return null;
                });
      }
      ready = column.value();
    }
    long started = System.nanoTime();
    columns.put(key, placed(ready));
    placeNanos += System.nanoTime() - started;
    return null;
  }

  /** Copies a column's regions and portals in at this graph's numbering. */
  private Placed placed(RegionColumn column) {
    int base = regionCount;
    long key = columnKey(column.chunkX, column.chunkZ);
    for (int r = 0; r < column.regionClass.length; r++) {
      int id = newRegion(RegionClass.ALL[column.regionClass[r]], key);
      System.arraycopy(column.regionBox, r * 6, regionBox, id * 6, 6);
      regionBreak[id] = column.regionBreak[r];
      regionNavigable[id] = column.regionNavigable[r];
    }
    int[] box = new int[6];
    for (int p = 0; p < column.portalA.length; p++) {
      System.arraycopy(column.portalBox, p * 6, box, 0, 6);
      newPortal(base + column.portalA[p], base + column.portalB[p], column.portalDirected[p], box);
    }
    return new Placed(column, base);
  }

  // ------------------------------------------------------------------------------------------
  // Seams
  // ------------------------------------------------------------------------------------------

  /**
   * Joins a column to its neighbour in positive X (or positive Z), once.
   *
   * <p>The same rules a column applies inside itself — faces touching, a walking step a level up or
   * down, a fall from a ledge — across the boundary between two. Both columns must be placed.
   */
  private void seam(int cx, int cz, boolean alongX) {
    long key = (columnKey(cx, cz) << 1) | (alongX ? 1 : 0);
    if (!seams.add(key)) {
      return;
    }
    Placed low = columns.get(columnKey(cx, cz));
    Placed high = columns.get(alongX ? columnKey(cx + 1, cz) : columnKey(cx, cz + 1));
    int ox = cx * 16;
    int oz = cz * 16;
    for (int ly = 0; ly < height; ly++) {
      int y = minY + ly;
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
        contacts.touch(ra, rb, wax, y, waz, wbx, y, wbz);
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
    contacts.flush(this::newPortal);
  }

  private void seamSteps(
      int from, Placed other, int lx, int ly, int lz, int fx, int fz, int tx, int tz) {
    int y = minY + ly;
    if (ly + 1 < height) {
      step(from, other.regionAt(lx, ly + 1, lz), fx, y, fz, tx, y + 1, tz);
    }
    if (ly > 0) {
      step(from, other.regionAt(lx, ly - 1, lz), fx, y, fz, tx, y - 1, tz);
    }
  }

  private void step(int from, int to, int fx, int fy, int fz, int tx, int ty, int tz) {
    if (to != NONE && to != from && RegionColumnBuilder.steppable(regionClassOf(to))) {
      contacts.touch(from, to, fx, fy, fz, tx, ty, tz);
    }
  }

  private void seamDrop(
      int from, Placed other, int lx, int ly, int lz, int fx, int fz, int tx, int tz) {
    int y = minY + ly;
    for (int k = 1; k <= RegionColumnBuilder.MAX_WATER_DROP && ly - k >= 0; k++) {
      int below = other.regionAt(lx, ly - k, lz);
      if (below == NONE) {
        return;
      }
      RegionClass c = regionClassOf(below);
      if (c == RegionClass.AIR) {
        continue;
      }
      if (RegionColumnBuilder.landing(c, k)) {
        contacts.drop(from, below, fx, y, fz, tx, y, tz);
      }
      return;
    }
  }

  // ------------------------------------------------------------------------------------------
  // Storage
  // ------------------------------------------------------------------------------------------

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

  private static long columnKey(int cx, int cz) {
    return ((long) cx << 32) | (cz & 0xFFFF_FFFFL);
  }

  private static int unpackX(long key) {
    return (int) (key >> 32);
  }

  private static int unpackZ(long key) {
    return (int) key;
  }

  /** A stored column, placed in this graph at an offset. */
  private static final class Placed {

    final RegionColumn column;
    final int base;
    boolean complete;

    Placed(RegionColumn column, int base) {
      this.column = column;
      this.base = base;
    }

    int regionAt(int lx, int ly, int lz) {
      int local = column.regionAt(lx, ly, lz);
      return local == RegionColumn.NONE ? NONE : base + local;
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
