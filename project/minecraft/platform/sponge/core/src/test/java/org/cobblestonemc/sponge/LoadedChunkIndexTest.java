/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cobblestonemc.CobblestoneLogger;
import org.junit.jupiter.api.Test;

/**
 * Tests the loaded-chunk index, whose whole job is to answer one question correctly from any
 * thread. A wrong answer is silent: say loaded when it is not and a fetch wastes a tick finding
 * out; say not loaded when it is and the search reads a stale chunk off disk.
 *
 * <p>Negative coordinates get their own attention because that is where a packing mistake hides —
 * they are also, on any world that has been explored west or north of spawn, most of them.
 */
class LoadedChunkIndexTest {

  private static final String WORLD = "minecraft:overworld";
  private static final String NETHER = "minecraft:the_nether";

  private final LoadedChunkIndex index = new LoadedChunkIndex(new SilentLogger());

  @Test
  void remembersAChunkItWasToldAbout() {
    index.markLoaded(WORLD, 4, 9);

    assertTrue(index.isLoaded(WORLD, 4, 9));
    assertFalse(index.isLoaded(WORLD, 4, 10));
    assertFalse(index.isLoaded(WORLD, 9, 4), "coordinates are not interchangeable");
  }

  @Test
  void forgetsAChunkThatUnloaded() {
    index.markLoaded(WORLD, 4, 9);
    index.markUnloaded(WORLD, 4, 9);

    assertFalse(index.isLoaded(WORLD, 4, 9));
  }

  @Test
  void answersNothingForAWorldItHasNeverSeen() {
    assertFalse(index.isLoaded("minecraft:the_end", 0, 0));
  }

  @Test
  void keepsWorldsApart() {
    index.markLoaded(WORLD, 4, 9);

    assertFalse(index.isLoaded(NETHER, 4, 9));
  }

  @Test
  void distinguishesNegativeCoordinatesFromPositiveOnes() {
    // The four sign combinations of one magnitude, which a packing that sign-extended the low word
    // or dropped the high one would collapse together.
    index.markLoaded(WORLD, -95, -6);

    assertTrue(index.isLoaded(WORLD, -95, -6));
    assertFalse(index.isLoaded(WORLD, -95, 6));
    assertFalse(index.isLoaded(WORLD, 95, -6));
    assertFalse(index.isLoaded(WORLD, 95, 6));
    assertFalse(index.isLoaded(WORLD, -6, -95), "and are still not interchangeable");
  }

  @Test
  void handlesTheExtremesOfTheCoordinateRange() {
    index.markLoaded(WORLD, Integer.MIN_VALUE, Integer.MAX_VALUE);
    index.markLoaded(WORLD, Integer.MAX_VALUE, Integer.MIN_VALUE);
    index.markLoaded(WORLD, -1, -1);

    assertTrue(index.isLoaded(WORLD, Integer.MIN_VALUE, Integer.MAX_VALUE));
    assertTrue(index.isLoaded(WORLD, Integer.MAX_VALUE, Integer.MIN_VALUE));
    assertTrue(index.isLoaded(WORLD, -1, -1));
    assertFalse(index.isLoaded(WORLD, Integer.MIN_VALUE, Integer.MIN_VALUE));
    assertFalse(index.isLoaded(WORLD, 0, 0));
  }

  @Test
  void unloadingOneChunkLeavesItsNeighboursAlone() {
    index.markLoaded(WORLD, -95, -6);
    index.markLoaded(WORLD, -95, -5);
    index.markLoaded(WORLD, -94, -6);

    index.markUnloaded(WORLD, -95, -6);

    assertFalse(index.isLoaded(WORLD, -95, -6));
    assertTrue(index.isLoaded(WORLD, -95, -5));
    assertTrue(index.isLoaded(WORLD, -94, -6));
  }

  @Test
  void unloadingSomethingUnknownIsHarmless() {
    index.markUnloaded(WORLD, 1, 1);
    index.markLoaded(WORLD, 2, 2);
    index.markUnloaded(WORLD, 1, 1);

    assertTrue(index.isLoaded(WORLD, 2, 2));
  }

  /** A logger that discards everything; these tests are about the index, not its narration. */
  private static final class SilentLogger extends CobblestoneLogger {
    @Override
    public void trace(String message, Object... args) {}

    @Override
    public void debug(String message, Object... args) {}

    @Override
    public void info(String message, Object... args) {}

    @Override
    public void warn(String message, Object... args) {}

    @Override
    public void error(String message, Throwable throwable, Object... args) {}
  }
}
