/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The suite takes no more than half of either the memory or the processors, and at least one. */
class VerifySuiteTest {

  private static final long GB = 1L << 30;

  @Test
  void halfTheProcessorsWhenMemoryIsPlenty() {
    assertEquals(10, VerifySuite.parallelism(128 * GB, 20));
  }

  @Test
  void halfTheFreeMemoryWhenProcessorsArePlenty() {
    // Half of 16 GB holds three routes of 2.5 GB each.
    assertEquals(3, VerifySuite.parallelism(16 * GB, 64));
  }

  @Test
  void alwaysAtLeastOne() {
    assertEquals(1, VerifySuite.parallelism(GB, 1));
  }
}
