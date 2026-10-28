/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.regions;

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
import org.jetbrains.annotations.Nullable;

/**
 * The domain as a graph of terrain regions joined by portals, as one search sees it.
 *
 * <p><b>Exact about topology, loose about geometry.</b> Every region is a flood-filled connected
 * piece of one class of terrain, so two regions are joined only where a body could actually cross
 * between them; but nothing inside a region is remembered beyond its bounding box, and the cost of
 * crossing one is a straight line priced at its class's rate. That is the trade this layer is built
 * on: a wrong price costs the fine search a few nodes, while a wrong connection — a wall reported
 * as a doorway — costs it thousands, so connections are computed from blocks and prices are allowed
 * to be rough.
 *
 * <p><b>Sections come from a {@link RegionStore}; seams are built here.</b> A section — every
 * region and portal inside one cube of terrain — is built once and shared by every search through
 * the store. This graph places each section it needs at an offset of its own, and joins
 * neighbouring sections the first time the search needs to leave one: seams need both sides, and
 * are cheap enough to compute per search. A section is <em>complete</em> once every seam and fall
 * that could reach its regions is built: its regions will never gain another portal, which is what
 * lets a search relax across a region once and trust that it saw everything.
 *
 * <p><b>What a seam holds.</b> Each join is built by exactly one seam, so a pair of regions is one
 * portal however many cells it touches along:
 *
 * <ul>
 *   <li>a <em>side seam</em>, between two sections side by side, holds their faces, the steps and
 *       falls off either's edge into the other's column, and the steps between the upper's floor
 *       and the lower's ceiling a level down beside it;
 *   <li>a <em>floor seam</em>, between a section and the one above it, holds their faces and the
 *       steps across them within the column;
 *   <li>a section's <em>falls</em> follow the ledges whose falls left it through its floor, which
 *       its builder kept, down into the sections below.
 * </ul>
 *
 * <p><b>Agent-independent.</b> Nothing here knows who is travelling: a region says what the terrain
 * is, and a {@link RegionAgent} decides what that is worth and whether it is passable at all.
 *
 * <p>Not thread-safe. One solve owns one graph, and the search that reads it is single-flight.
 *
 * @param <C> the platform's terrain
 * @param <K> the terrain's classes
 */
public final class RegionGraph<C, K extends Enum<K> & RegionKind> {

  /** No region: unbreakable, lava, outside the domain, or a section that cannot be read. */
  public static final int NONE = -1;

  /** The column of sections a section's seams reach into, by X and Z offset. */
  private static final int[][] COLUMNS = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}};

  private final RegionStore<C, K> store;
  private final RegionSpec<C, K> spec;
  private final TerrainSource<C> source;
  private final int shift;
  private final int mask;
  private final int width;

  /** How many sections below its own a fall can land in. */
  private final int reach;

  private final Map<Long, Placed> sections = new HashMap<>();
  private final Set<Long> sideSeamsX = new HashSet<>();
  private final Set<Long> sideSeamsZ = new HashSet<>();
  private final Set<Long> floorSeams = new HashSet<>();
  private final Set<Long> falls = new HashSet<>();

  /**
   * Sections the store handed over after this graph had to wait for them, by section.
   *
   * <p>Kept here rather than asked for again: the store has them in memory too, but under a tight
   * budget it may already have evicted one, and a search asking again would wait again.
   */
  private final Map<Long, RegionSection> arrived = new ConcurrentHashMap<>();

  private final RegionJoins joins;

  /** Every placed section's cells, in this graph's numbering, as the joining rules read them. */
  private final RegionJoins.Cells cells;

  // --- the two sections looked up last: a seam reads back and forth between two ---
  private long hotKey;
  private @Nullable Placed hot;
  private long warmKey;
  private @Nullable Placed warm;

  // --- regions, struct of arrays ---
  private int regionCount;
  private byte[] regionClass = new byte[1024];
  private int[] regionBox = new int[6 * 1024];
  private long[] regionSection = new long[1024];
  private int[][] regionPortals = new int[1024][];
  private int[] regionPortalCount = new int[1024];

  // --- portals ---
  private int portalCount;
  private int[] portalA = new int[1024];
  private int[] portalB = new int[1024];
  private byte[] portalFall = new byte[1024];
  private int[] portalBox = new int[6 * 1024];

  private long placeNanos;
  private long seamNanos;

  /**
   * Creates a graph over a store.
   *
   * @param store where built sections are kept: a domain's shared store, or one of the search's own
   *     ({@link RegionStore#inMemory})
   * @param source where this search reads a section the store has to build
   */
  public RegionGraph(RegionStore<C, K> store, TerrainSource<C> source) {
    this.store = store;
    this.spec = store.spec();
    this.source = source;
    this.shift = spec.shift();
    this.width = spec.width();
    this.mask = width - 1;
    this.reach = spec.fallSections();
    this.joins =
        new RegionJoins(region -> spec.classOf(regionClass[region]), spec.terrain().deepestFall());
    this.cells =
        (x, y, z) -> {
          Placed placed = placedAt(x >> shift, y >> shift, z >> shift);
          return placed == null
              ? RegionJoins.OUTSIDE
              : placed.regionAt(x & mask, y & mask, z & mask);
        };
  }

  // ------------------------------------------------------------------------------------------
  // Reading
  // ------------------------------------------------------------------------------------------

  /**
   * Returns the region at a cell, or {@link #NONE}, if its section is placed.
   *
   * @param x the X
   * @param y the Y
   * @param z the Z
   * @return the region, or {@link #NONE} — including when the section has not been placed
   */
  public int regionAt(int x, int y, int z) {
    int region = cells.regionAt(x, y, z);
    return region < 0 ? NONE : region;
  }

  /**
   * Returns a region's class.
   *
   * @param region the region
   * @return its class
   */
  public K regionClass(int region) {
    return spec.classOf(regionClass[region]);
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
    return portalFall[portal] != 0;
  }

  /**
   * Returns how far a body falls through a portal, from {@link #portalA} into {@link #portalB}.
   *
   * @param portal the portal
   * @return the depth in blocks, or 0 if the portal is no fall
   */
  public int fall(int portal) {
    return portalFall[portal];
  }

  /**
   * Returns one bound of a portal's box: the cells on both sides of the contact.
   *
   * @param portal the portal
   * @param bound 0–2 the minimum X, Y, Z and 3–5 the maximum
   * @return the bound, in domain coordinates
   */
  public int portalBound(int portal, int bound) {
    return portalBox[portal * 6 + bound];
  }

  /**
   * Returns one bound of a region's box.
   *
   * @param region the region
   * @param bound 0–2 the minimum X, Y, Z and 3–5 the maximum
   * @return the bound, in domain coordinates
   */
  public int regionBound(int region, int bound) {
    return regionBox[region * 6 + bound];
  }

  /**
   * Returns the section holding a coordinate, along any axis.
   *
   * @param coordinate the X, Y or Z
   * @return the section's X, Y or Z
   */
  public int sectionOf(int coordinate) {
    return coordinate >> shift;
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
   * Returns the time spent placing stored sections into this graph: copying their regions and
   * portals in at this graph's numbering. Building a section is the store's, and counted there.
   *
   * @return nanoseconds
   */
  public long placeNanos() {
    return placeNanos;
  }

  /**
   * Returns the time spent joining sections at their seams and following their falls down.
   *
   * @return nanoseconds
   */
  public long seamNanos() {
    return seamNanos;
  }

  /**
   * Returns roughly how much memory this graph holds of its own, in bytes.
   *
   * <p>Its regions and portals, which it copies; not the sections' cell maps, which it shares with
   * the store.
   *
   * @return the approximate footprint
   */
  public long footprintBytes() {
    long bytes = sections.size() * 64L;
    // Per region: class, box, section, portal list header and count.
    bytes += regionCount * (1L + 24 + 8 + 16 + 4);
    for (int region = 0; region < regionCount; region++) {
      bytes += regionPortals[region].length * 4L;
    }
    // Per portal: both regions, the depth, and the box.
    bytes += portalCount * (4L + 4 + 1 + 24);
    return bytes;
  }

  // ------------------------------------------------------------------------------------------
  // Completing
  // ------------------------------------------------------------------------------------------

  /**
   * Makes sure the section holding a cell is placed and complete, so every region in it has all of
   * its portals.
   *
   * @param x the X
   * @param y the Y
   * @param z the Z
   * @return a future completing when the sections it needs arrive, or {@code null} if it is ready
   */
  public @Nullable CompletableFuture<Void> complete(int x, int y, int z) {
    return completeSection(x >> shift, y >> shift, z >> shift);
  }

  /**
   * Makes sure a region's section is complete.
   *
   * @param region the region
   * @return a future completing when the sections it needs arrive, or {@code null} if it is ready
   */
  public @Nullable CompletableFuture<Void> completeRegion(int region) {
    long key = regionSection[region];
    Placed section = sections.get(key);
    if (section != null && section.complete) {
      return null; // the common case, and it allocates nothing
    }
    return completeSection(
        RegionSection.keyX(key), RegionSection.keyY(key), RegionSection.keyZ(key));
  }

  /**
   * Completes a section: places every section its seams and falls read, then builds them.
   *
   * <p>The seams that can reach its regions are the side seams with its four neighbours from a
   * level below it, whose ceiling a step crosses, to as many above as a fall can drop from; its two
   * floor seams; and its own falls and those of the sections above it a fall can drop from. Those
   * read one more level beyond each end, so the columns around it are placed that far up and down.
   */
  private @Nullable CompletableFuture<Void> completeSection(int sx, int sy, int sz) {
    Placed section = sections.get(RegionSection.key(sx, sy, sz));
    if (section != null && section.complete) {
      return null;
    }
    // Every section a seam reads, asked for together: one park for all of them.
    List<CompletableFuture<?>> waiting = null;
    for (int[] column : COLUMNS) {
      for (int dy = -1 - reach; dy <= 1 + reach; dy++) {
        CompletableFuture<?> pending = place(sx + column[0], sy + dy, sz + column[1]);
        if (pending != null) {
          if (waiting == null) {
            waiting = new ArrayList<>();
          }
          waiting.add(pending);
        }
      }
    }
    if (waiting != null) {
      return CompletableFuture.allOf(waiting.toArray(new CompletableFuture<?>[0]));
    }
    long started = System.nanoTime();
    for (int level = sy - 1; level <= sy + reach; level++) {
      sideSeam(sx, level, sz, true);
      sideSeam(sx - 1, level, sz, true);
      sideSeam(sx, level, sz, false);
      sideSeam(sx, level, sz - 1, false);
    }
    floorSeam(sx, sy - 1, sz);
    floorSeam(sx, sy, sz);
    for (int level = sy; level <= sy + reach; level++) {
      falls(sx, level, sz);
    }
    seamNanos += System.nanoTime() - started;
    sections.get(RegionSection.key(sx, sy, sz)).complete = true;
    return null;
  }

  /** Places a section if the store has it ready, or returns the future it arrives on. */
  private @Nullable CompletableFuture<?> place(int sx, int sy, int sz) {
    long key = RegionSection.key(sx, sy, sz);
    if (sections.containsKey(key)) {
      return null;
    }
    RegionSection ready = arrived.remove(key);
    if (ready == null) {
      FutureOr<RegionSection> section = store.section(sx, sy, sz, source);
      if (!section.isImmediate()) {
        // Kept as it lands, so the search finds it on waking whatever the store does meanwhile. A
        // failure is a section that cannot be known, like an ungenerated one.
        return section
            .future()
            .handle(
                (built, failure) -> {
                  arrived.put(key, built != null ? built : RegionSection.empty(spec, sx, sy, sz));
                  return null;
                });
      }
      ready = section.value();
    }
    long started = System.nanoTime();
    sections.put(key, placed(ready, key));
    placeNanos += System.nanoTime() - started;
    return null;
  }

  /** Copies a section's regions and portals in at this graph's numbering. */
  private Placed placed(RegionSection section, long key) {
    int base = regionCount;
    boolean standing = false;
    for (int r = 0; r < section.regionClass.length; r++) {
      int id = newRegion(section.regionClass[r], key);
      System.arraycopy(section.regionBox, r * 6, regionBox, id * 6, 6);
      standing |= spec.classOf(section.regionClass[r]).standing();
    }
    int[] box = new int[6];
    for (int p = 0; p < section.portalA.length; p++) {
      System.arraycopy(section.portalBox, p * 6, box, 0, 6);
      newPortal(base + section.portalA[p], base + section.portalB[p], section.portalFall[p], box);
    }
    return new Placed(section, base, standing);
  }

  private @Nullable Placed placedAt(int sx, int sy, int sz) {
    long key = RegionSection.key(sx, sy, sz);
    if (hot != null && key == hotKey) {
      return hot;
    }
    if (warm != null && key == warmKey) {
      Placed found = warm;
      warm = hot;
      warmKey = hotKey;
      hot = found;
      hotKey = key;
      return found;
    }
    Placed found = sections.get(key);
    if (found != null) {
      warm = hot;
      warmKey = hotKey;
      hot = found;
      hotKey = key;
    }
    return found;
  }

  // ------------------------------------------------------------------------------------------
  // Seams
  // ------------------------------------------------------------------------------------------

  /**
   * Joins a section to its neighbour in positive X (or positive Z), once.
   *
   * <p>The same rules a section applies inside itself — faces touching, a walking step a level up
   * or down, a fall from a ledge — across the boundary between two. A step down from a cell on
   * either's floor is the seam below's, which holds the steps down onto its ceiling from the floor
   * above, so a step between two levels is one seam's whichever side it is taken from.
   */
  private void sideSeam(int sx, int sy, int sz, boolean alongX) {
    if (!(alongX ? sideSeamsX : sideSeamsZ).add(RegionSection.key(sx, sy, sz))) {
      return;
    }
    int nx = alongX ? sx + 1 : sx;
    int nz = alongX ? sz : sz + 1;
    Placed low = placedAt(sx, sy, sz);
    Placed high = placedAt(nx, sy, nz);
    Placed lowAbove = placedAt(sx, sy + 1, sz);
    Placed highAbove = placedAt(nx, sy + 1, nz);
    int ox = sx << shift;
    int oy = sy << shift;
    int oz = sz << shift;
    if (low.section.uniform() && high.section.uniform()) {
      // Two whole regions face to face: one contact, the whole face.
      int ra = low.uniform();
      int rb = high.uniform();
      int ax = alongX ? ox + mask : ox;
      int az = alongX ? oz : oz + mask;
      int bx = alongX ? ox + width : ox;
      int bz = alongX ? oz : oz + width;
      // The far corner of the face: along Z for a seam along X, and along X for one along Z.
      int fx = alongX ? 0 : mask;
      int fz = alongX ? mask : 0;
      joins.touch(ra, rb, ax, oy, az, bx, oy, bz);
      joins.touch(ra, rb, ax + fx, oy + mask, az + fz, bx + fx, oy + mask, bz + fz);
    }
    for (int t = 0; t < width; t++) {
      // a is the low section's edge cell, b the high section's, face to face.
      int lax = alongX ? mask : t;
      int laz = alongX ? t : mask;
      int lbx = alongX ? 0 : t;
      int lbz = alongX ? t : 0;
      int ax = ox + lax;
      int az = oz + laz;
      int bx = alongX ? ox + width : ox + t;
      int bz = alongX ? oz + t : oz + width;
      if (!low.section.uniform() || !high.section.uniform()) {
        for (int ly = 0; ly < width; ly++) {
          joins.touch(
              low.regionAt(lax, ly, laz),
              high.regionAt(lbx, ly, lbz),
              ax,
              oy + ly,
              az,
              bx,
              oy + ly,
              bz);
        }
      }
      if (low.standing || high.standing) {
        for (int ly = 0; ly < width; ly++) {
          int y = oy + ly;
          ledge(low.regionAt(lax, ly, laz), ax, y, az, bx, bz, ly > 0);
          ledge(high.regionAt(lbx, ly, lbz), bx, y, bz, ax, az, ly > 0);
        }
      }
      // The floor above, stepping down onto this level's ceiling beside it.
      int y = oy + width;
      if (lowAbove.standing) {
        int ra = lowAbove.regionAt(lax, 0, laz);
        if (joins.standing(ra)) {
          joins.stepDown(ra, ax, y, az, cells, bx, bz);
        }
      }
      if (highAbove.standing) {
        int rb = highAbove.regionAt(lbx, 0, lbz);
        if (joins.standing(rb)) {
          joins.stepDown(rb, bx, y, bz, cells, ax, az);
        }
      }
    }
    joins.flush(this::newPortal);
  }

  /** Joins a standing cell on a seam to the cell beside it across: steps, and a fall. */
  private void ledge(int from, int fx, int y, int fz, int tx, int tz, boolean down) {
    if (!joins.standing(from)) {
      return;
    }
    joins.stepUp(from, fx, y, fz, cells, tx, tz);
    if (down) {
      joins.stepDown(from, fx, y, fz, cells, tx, tz);
    }
    joins.drop(from, fx, y, fz, cells, tx, tz);
  }

  /**
   * Joins a section to the one above it, once: faces, and the steps across between the two within
   * the column. A step that also crosses into the next column is that side seam's.
   */
  private void floorSeam(int sx, int sy, int sz) {
    if (!floorSeams.add(RegionSection.key(sx, sy, sz))) {
      return;
    }
    Placed lower = placedAt(sx, sy, sz);
    Placed upper = placedAt(sx, sy + 1, sz);
    int ox = sx << shift;
    int y = (sy << shift) + mask;
    int oz = sz << shift;
    if (lower.section.uniform() && upper.section.uniform()) {
      int ra = lower.uniform();
      int rb = upper.uniform();
      joins.touch(ra, rb, ox, y, oz, ox, y + 1, oz);
      joins.touch(ra, rb, ox + mask, y, oz + mask, ox + mask, y + 1, oz + mask);
    } else {
      for (int z = 0; z < width; z++) {
        for (int x = 0; x < width; x++) {
          joins.touch(
              lower.regionAt(x, mask, z),
              upper.regionAt(x, 0, z),
              ox + x,
              y,
              oz + z,
              ox + x,
              y + 1,
              oz + z);
        }
      }
    }
    for (int z = 0; z < width && (lower.standing || upper.standing); z++) {
      for (int x = 0; x < width; x++) {
        int below = lower.regionAt(x, mask, z);
        int above = upper.regionAt(x, 0, z);
        boolean up = joins.standing(below);
        boolean down = joins.standing(above);
        if (!up && !down) {
          continue;
        }
        for (int[] h : RegionSection.DIRECTIONS) {
          int nx = x + h[0];
          int nz = z + h[1];
          if (nx < 0 || nx > mask || nz < 0 || nz > mask) {
            continue; // into the next column: a side seam's
          }
          if (up) {
            joins.stepUp(below, ox + x, y, oz + z, cells, ox + nx, oz + nz);
          }
          if (down) {
            joins.stepDown(above, ox + x, y + 1, oz + z, cells, ox + nx, oz + nz);
          }
        }
      }
    }
    joins.flush(this::newPortal);
  }

  /** Follows a section's falls that leave through its floor down to where they land, once. */
  private void falls(int sx, int sy, int sz) {
    if (!falls.add(RegionSection.key(sx, sy, sz))) {
      return;
    }
    Placed section = placedAt(sx, sy, sz);
    int[] exits = section.section.exits;
    if (exits.length == 0) {
      return;
    }
    int ox = sx << shift;
    int oy = sy << shift;
    int oz = sz << shift;
    for (int exit : exits) {
      int cell = exit >>> 2;
      int[] h = RegionSection.DIRECTIONS[exit & 3];
      int x = ox + (cell & mask);
      int y = oy + (cell >> (2 * shift));
      int z = oz + ((cell >> shift) & mask);
      int from = section.base + section.section.regionAt(cell);
      joins.drop(from, x, y, z, cells, x + h[0], z + h[1]);
    }
    joins.flush(this::newPortal);
  }

  // ------------------------------------------------------------------------------------------
  // Storage
  // ------------------------------------------------------------------------------------------

  private int newRegion(int clazz, long section) {
    if (regionCount == regionClass.length) {
      int size = regionCount * 2;
      regionClass = Arrays.copyOf(regionClass, size);
      regionBox = Arrays.copyOf(regionBox, size * 6);
      regionSection = Arrays.copyOf(regionSection, size);
      regionPortals = Arrays.copyOf(regionPortals, size);
      regionPortalCount = Arrays.copyOf(regionPortalCount, size);
    }
    int id = regionCount++;
    regionClass[id] = (byte) clazz;
    regionSection[id] = section;
    regionPortals[id] = new int[4];
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

  /** A stored section, placed in this graph at an offset. */
  private static final class Placed {

    final RegionSection section;
    final int base;

    /** Whether any of its regions is one a body stands in, so its seams hold steps and falls. */
    final boolean standing;

    boolean complete;

    Placed(RegionSection section, int base, boolean standing) {
      this.section = section;
      this.base = base;
      this.standing = standing;
    }

    int regionAt(int lx, int ly, int lz) {
      int local = section.regionAt(lx, ly, lz);
      return local == RegionSection.NONE ? NONE : base + local;
    }

    /** The region filling a section with no cell map, or {@link #NONE}. */
    int uniform() {
      return section.uniform == RegionSection.NONE ? NONE : base + section.uniform;
    }
  }
}
