/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.minecraft;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import net.whimxiqal.odyssey.api.Cell;
import net.whimxiqal.odyssey.api.FutureOr;
import net.whimxiqal.odyssey.minecraft.api.MinecraftBlock;
import net.whimxiqal.odyssey.minecraft.api.MinecraftWorld;

/**
 * An in-memory {@link MinecraftWorld} for mode tests. Cells default to air; set blocks with the
 * builder. Every block is served immediately (no parking), exercising the cache-hit path.
 */
public final class TestWorld implements MinecraftWorld {

  private final String key;
  private final Map<Cell, MinecraftBlock> grid;
  private final MinecraftBlock air = TestBlocks.air();

  private TestWorld(String key, Map<Cell, MinecraftBlock> grid) {
    this.key = key;
    this.grid = grid;
  }

  public static Builder builder(String key) {
    return new Builder(key);
  }

  @Override
  public int minY() {
    return -64;
  }

  @Override
  public int maxY() {
    return 320;
  }

  @Override
  public String key() {
    return key;
  }

  @Override
  public Environment environment() {
    return Environment.OVERWORLD;
  }

  @Override
  public FutureOr<MinecraftBlock> blockAt(Cell cell) {
    return FutureOr.of(grid.getOrDefault(cell, air));
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof TestWorld other && key.equals(other.key);
  }

  @Override
  public int hashCode() {
    return Objects.hash(key);
  }

  /** Builds a {@link TestWorld}. */
  public static final class Builder {

    private final String key;
    private final Map<Cell, MinecraftBlock> grid = new HashMap<>();

    private Builder(String key) {
      this.key = key;
    }

    public Builder set(int x, int y, int z, MinecraftBlock block) {
      grid.put(new Cell(x, y, z), block);
      return this;
    }

    /** Fills a solid floor at {@code y} over the inclusive x/z rectangle. */
    public Builder floor(int y, int x0, int z0, int x1, int z1, MinecraftBlock block) {
      for (int x = x0; x <= x1; x++) {
        for (int z = z0; z <= z1; z++) {
          grid.put(new Cell(x, y, z), block);
        }
      }
      return this;
    }

    public TestWorld build() {
      return new TestWorld(key, grid);
    }
  }
}
