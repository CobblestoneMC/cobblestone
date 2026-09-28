/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.lod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.cobblestonemc.minecraft.MinecraftBlock;
import org.cobblestonemc.minecraft.MinecraftChunk;

/**
 * Summarizes one 16³ section into a {@link SectionProfile}.
 *
 * <p><b>One flood fill, then counting.</b> The fill splits the section's open space into connected
 * components, which is what stops a profile claiming a crossing is possible when a wall runs down
 * the middle. Everything after that is a single pass incrementing per-axis bitmasks — no search per
 * medium, and nothing that needs to be re-implemented when levels are aggregated.
 *
 * <p>The cost is dominated by reading the blocks, which is one chunk fetch the caller has usually
 * paid for anyway. Profiling the whole column while the chunk is in hand is therefore close to free
 * next to profiling one section of it.
 *
 * <p>Not thread-safe; construct one per thread, or one per profiling pass. The scratch arrays exist
 * precisely so that profiling a 24-section column does not allocate 24 times.
 */
public final class SectionProfiler {

  private static final int SIZE = 16;
  private static final int VOLUME = SIZE * SIZE * SIZE;

  /** Which component each cell belongs to, or {@code -1} for solid. Reused between sections. */
  private final int[] componentOf = new int[VOLUME];

  /** Queue for the flood fill, as cell indices. Reused between sections. */
  private final int[] queue = new int[VOLUME];

  private final MinecraftBlock[] blocks = new MinecraftBlock[VOLUME];

  /**
   * The block layer immediately below and above the section, indexed by {@code z * 16 + x}.
   *
   * <p>⚠️ Without these, a section of open air sitting directly on terrain profiles as
   * <b>unwalkable</b>: the cells a body would actually walk on are at its very bottom, and their
   * floor is the top of the section below. That is not a corner case — it is where surface routes
   * spend nearly all of their time — and it made the coarse estimate price ordinary ground at the
   * fallback rate. The chunk can answer for these rows as easily as for any other, so there is no
   * reason to pretend the section is all we can see.
   */
  private final MinecraftBlock[] below = new MinecraftBlock[SIZE * SIZE];

  private final MinecraftBlock[] above = new MinecraftBlock[SIZE * SIZE];

  /**
   * Profiles one section of a chunk.
   *
   * @param chunk the chunk
   * @param sectionY the section index — world Y divided by 16, floored
   * @return the profile
   */
  public SectionProfile profile(MinecraftChunk chunk, int sectionY) {
    int floor = sectionY * SIZE;
    for (int y = 0; y < SIZE; y++) {
      for (int z = 0; z < SIZE; z++) {
        for (int x = 0; x < SIZE; x++) {
          blocks[index(x, y, z)] = chunk.block(x, floor + y, z);
        }
      }
    }
    for (int z = 0; z < SIZE; z++) {
      for (int x = 0; x < SIZE; x++) {
        below[z * SIZE + x] = chunk.block(x, floor - 1, z);
        above[z * SIZE + x] = chunk.block(x, floor + SIZE, z);
      }
    }
    return profileBlocks();
  }

  private SectionProfile profileBlocks() {
    int componentCount = fillComponents();

    if (componentCount == 0) {
      // Nothing can be occupied, but it can still be dug through — and a coarse search that treated
      // solid ground as absent would never consider mining at all, which is a route Cobblestone
      // genuinely offers.
      SectionProfile.Component rock = solidComponent();
      return new SectionProfile(List.of(rock), rock, false);
    }

    List<Raw> raws = new ArrayList<>(componentCount);
    for (int i = 0; i < componentCount; i++) {
      raws.add(new Raw(i));
    }
    for (int cell = 0; cell < VOLUME; cell++) {
      int component = componentOf[cell];
      if (component >= 0) {
        raws.get(component).add(cell);
      }
    }
    // Mining is not confined to a component — digging is how you leave one — so it is measured over
    // the whole section and given to every component.
    MineStats mine = mineStats();

    raws.sort(Comparator.comparingInt((Raw raw) -> raw.volume).reversed());
    boolean merged = raws.size() > SectionProfile.MAX_COMPONENTS;
    if (merged) {
      List<Raw> kept = new ArrayList<>(raws.subList(0, SectionProfile.MAX_COMPONENTS));
      Raw last = kept.get(SectionProfile.MAX_COMPONENTS - 1);
      for (Raw dropped : raws.subList(SectionProfile.MAX_COMPONENTS, raws.size())) {
        last.absorb(dropped);
      }
      raws = kept;
    }

    List<SectionProfile.Component> components = new ArrayList<>(raws.size());
    for (Raw raw : raws) {
      components.add(raw.build(mine));
    }
    // The whole section as one component: every open cell, regardless of which pocket it is in.
    // This is what the coarse graph uses; see SectionProfile#whole.
    Raw everything = new Raw(-1);
    for (int cell = 0; cell < VOLUME; cell++) {
      if (componentOf[cell] >= 0) {
        everything.add(cell);
      }
    }
    return new SectionProfile(components, everything.build(mine), merged);
  }

  /**
   * Whether a body can be inside this cell without breaking anything.
   *
   * <p><b>Not {@code isPassable()}.</b> That method deliberately excludes water — the block model
   * leaves water to the swim and danger logic — so filling on it alone would leave every water cell
   * out of every component, and a lake would profile as though a boat could not float on it. Lava
   * is excluded: a body can occupy it, briefly, but nothing routes through it on purpose.
   */
  private static boolean open(MinecraftBlock block) {
    return block.isPassable() || block.isWater();
  }

  /**
   * Flood-fills the section's occupiable cells into components, returning how many there are.
   *
   * <p>Six-connected: a body moves through faces, and treating a diagonal gap as a connection would
   * claim a crossing through a corner that nothing can actually pass.
   */
  private int fillComponents() {
    java.util.Arrays.fill(componentOf, -1);
    int count = 0;
    for (int start = 0; start < VOLUME; start++) {
      if (componentOf[start] >= 0 || !open(blocks[start])) {
        continue;
      }
      int head = 0;
      int tail = 0;
      queue[tail++] = start;
      componentOf[start] = count;
      while (head < tail) {
        int cell = queue[head++];
        int x = cell & 15;
        int y = (cell >> 8) & 15;
        int z = (cell >> 4) & 15;
        if (x > 0) {
          tail = visit(index(x - 1, y, z), count, tail);
        }
        if (x < SIZE - 1) {
          tail = visit(index(x + 1, y, z), count, tail);
        }
        if (y > 0) {
          tail = visit(index(x, y - 1, z), count, tail);
        }
        if (y < SIZE - 1) {
          tail = visit(index(x, y + 1, z), count, tail);
        }
        if (z > 0) {
          tail = visit(index(x, y, z - 1), count, tail);
        }
        if (z < SIZE - 1) {
          tail = visit(index(x, y, z + 1), count, tail);
        }
      }
      count++;
    }
    return count;
  }

  private int visit(int cell, int component, int tail) {
    if (componentOf[cell] < 0 && open(blocks[cell])) {
      componentOf[cell] = component;
      queue[tail++] = cell;
    }
    return tail;
  }

  /** What mining through this section would cost, and whether it is possible at all. */
  private record MineStats(boolean[] slices, double averageBreakTime) {}

  private MineStats mineStats() {
    boolean[] slices = new boolean[SIZE * 3];
    double total = 0;
    int counted = 0;
    for (int cell = 0; cell < VOLUME; cell++) {
      MinecraftBlock block = blocks[cell];
      if (open(block) || !Medium.MINE.occupies(block)) {
        continue;
      }
      mark(slices, cell);
      total += block.breakTimeSeconds();
      counted++;
    }
    return new MineStats(slices, counted == 0 ? 0 : total / counted);
  }

  private static void mark(boolean[] slices, int cell) {
    slices[(cell & 15)] = true;
    slices[SIZE + ((cell >> 8) & 15)] = true;
    slices[SIZE * 2 + ((cell >> 4) & 15)] = true;
  }

  private SectionProfile.Component solidComponent() {
    MineStats mine = mineStats();
    byte[] coverage = new byte[SectionProfile.Axis.ALL.length * Medium.COUNT];
    applyMine(coverage, mine);
    // Solid rock touches every face: mining enters and leaves it from any side.
    return new SectionProfile.Component(coverage, 0b111111, 0, mine.averageBreakTime());
  }

  private static void applyMine(byte[] coverage, MineStats mine) {
    for (SectionProfile.Axis axis : SectionProfile.Axis.ALL) {
      int count = 0;
      for (int slice = 0; slice < SIZE; slice++) {
        if (mine.slices()[axis.ordinal() * SIZE + slice]) {
          count++;
        }
      }
      coverage[axis.ordinal() * Medium.COUNT + Medium.MINE.ordinal()] = (byte) count;
    }
  }

  /** Per-component accumulation: which slices each medium reaches, along each axis. */
  private final class Raw {

    private final int id;
    private final boolean[] slices =
        new boolean[SectionProfile.Axis.ALL.length * SIZE * Medium.COUNT];
    private int volume;
    private int faceMask;

    Raw(int id) {
      this.id = id;
    }

    void add(int cell) {
      volume++;
      int x = cell & 15;
      int y = (cell >> 8) & 15;
      int z = (cell >> 4) & 15;
      if (x == 0) {
        faceMask |= 1 << SectionProfile.Face.WEST.ordinal();
      }
      if (x == SIZE - 1) {
        faceMask |= 1 << SectionProfile.Face.EAST.ordinal();
      }
      if (y == 0) {
        faceMask |= 1 << SectionProfile.Face.DOWN.ordinal();
      }
      if (y == SIZE - 1) {
        faceMask |= 1 << SectionProfile.Face.UP.ordinal();
      }
      if (z == 0) {
        faceMask |= 1 << SectionProfile.Face.NORTH.ordinal();
      }
      if (z == SIZE - 1) {
        faceMask |= 1 << SectionProfile.Face.SOUTH.ordinal();
      }
      MinecraftBlock block = blocks[cell];
      for (Medium medium : Medium.ALL) {
        if (medium == Medium.MINE) {
          continue; // measured section-wide
        }
        if (medium == Medium.WALK
            ? walkable(cell)
            : medium == Medium.BOAT ? navigable(cell) : medium.occupies(block)) {
          markMedium(medium, cell);
        }
      }
    }

    private void markMedium(Medium medium, int cell) {
      int stride = SIZE * Medium.COUNT;
      slices[(cell & 15) * Medium.COUNT + medium.ordinal()] = true;
      slices[stride + ((cell >> 8) & 15) * Medium.COUNT + medium.ordinal()] = true;
      slices[stride * 2 + ((cell >> 4) & 15) * Medium.COUNT + medium.ordinal()] = true;
    }

    void absorb(Raw other) {
      volume += other.volume;
      faceMask |= other.faceMask;
      for (int i = 0; i < slices.length; i++) {
        slices[i] |= other.slices[i];
      }
    }

    SectionProfile.Component build(MineStats mine) {
      byte[] coverage = new byte[SectionProfile.Axis.ALL.length * Medium.COUNT];
      int stride = SIZE * Medium.COUNT;
      for (SectionProfile.Axis axis : SectionProfile.Axis.ALL) {
        for (Medium medium : Medium.ALL) {
          if (medium == Medium.MINE) {
            continue;
          }
          int count = 0;
          for (int slice = 0; slice < SIZE; slice++) {
            if (slices[axis.ordinal() * stride + slice * Medium.COUNT + medium.ordinal()]) {
              count++;
            }
          }
          coverage[axis.ordinal() * Medium.COUNT + medium.ordinal()] = (byte) count;
        }
      }
      applyMine(coverage, mine);
      return new SectionProfile.Component(coverage, faceMask, volume, mine.averageBreakTime());
    }
  }

  /**
   * Whether a body can stand here: footing below, and headroom for a two-block body.
   *
   * <p>The one medium that cannot be decided from its own cell, which is why {@link
   * Medium#occupies} declines to answer for it.
   */
  private boolean walkable(int cell) {
    int x = cell & 15;
    int y = (cell >> 8) & 15;
    int z = (cell >> 4) & 15;
    MinecraftBlock floor = y == 0 ? below[z * SIZE + x] : blocks[index(x, y - 1, z)];
    MinecraftBlock head = y == SIZE - 1 ? above[z * SIZE + x] : blocks[index(x, y + 1, z)];
    return floor.isSolidTop() && head.isPassable();
  }

  /** Whether a boat floats here: water with something other than water directly above. */
  private boolean navigable(int cell) {
    MinecraftBlock block = blocks[cell];
    if (!block.supportsBoat()) {
      return false;
    }
    int x = cell & 15;
    int y = (cell >> 8) & 15;
    int z = (cell >> 4) & 15;
    MinecraftBlock over = y == SIZE - 1 ? above[z * SIZE + x] : blocks[index(x, y + 1, z)];
    return !over.isWater();
  }

  private static int index(int x, int y, int z) {
    return (y << 8) | (z << 4) | x;
  }
}
