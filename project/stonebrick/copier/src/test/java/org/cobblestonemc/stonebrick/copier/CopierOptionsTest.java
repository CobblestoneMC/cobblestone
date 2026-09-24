/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.copier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CopierOptionsTest {

  @Test
  void defaultsAreASurfaceBandIntoTheDefaultCapture() throws Exception {
    CopierOptions options = CopierOptions.parse("", "corpus");

    assertEquals("corpus", options.captureName());
    assertNull(options.worldKey());
    assertFalse(options.blockCoordinates());
    assertEquals(new VerticalMode.Surface(24, 24), options.vertical());
  }

  @Test
  void everyFlagParses() throws Exception {
    CopierOptions options =
        CopierOptions.parse(
            "--name river --world minecraft:the_nether --blocks --overwrite --force --y -64 40",
            "corpus");

    assertEquals("river", options.captureName());
    assertEquals("minecraft:the_nether", options.worldKey());
    assertTrue(options.blockCoordinates());
    assertTrue(options.overwrite());
    assertTrue(options.force());
    assertEquals(new VerticalMode.Range(-64, 40), options.vertical());
  }

  @Test
  void verticalModesAreMutuallyExclusive() {
    CopierOptions.ParseException thrown =
        assertThrows(
            CopierOptions.ParseException.class,
            () -> CopierOptions.parse("--full --surface 8 8", "corpus"));
    assertTrue(thrown.getMessage().contains("--full"), thrown.getMessage());
    assertTrue(thrown.getMessage().contains("--surface"), thrown.getMessage());
  }

  @Test
  void badInputIsRejectedWithSomethingActionable() {
    assertTrue(
        assertThrows(
                CopierOptions.ParseException.class, () -> CopierOptions.parse("--name", "corpus"))
            .getMessage()
            .contains("--name needs"));
    assertTrue(
        assertThrows(
                CopierOptions.ParseException.class,
                () -> CopierOptions.parse("--surface 8 tall", "corpus"))
            .getMessage()
            .contains("tall"));
    assertTrue(
        assertThrows(
                CopierOptions.ParseException.class,
                () -> CopierOptions.parse("--depth 8", "corpus"))
            .getMessage()
            .contains("--depth"));
    // A reversed range is a typo, not an empty capture.
    assertThrows(
        CopierOptions.ParseException.class, () -> CopierOptions.parse("--y 40 -64", "corpus"));
  }

  @Test
  void optionsRenderBackAsCommandArguments() throws Exception {
    // The estimate report prints the mode so it can be pasted straight back into `copy`.
    assertEquals("--surface 8 16", new VerticalMode.Surface(8, 16).toCommandArguments());
    assertEquals("--y -64 40", new VerticalMode.Range(-64, 40).toCommandArguments());
    assertEquals("--full", new VerticalMode.Full().toCommandArguments());
    assertEquals(
        new VerticalMode.Surface(8, 16),
        CopierOptions.parse(new VerticalMode.Surface(8, 16).toCommandArguments(), "c").vertical());
  }

  @Test
  void aSurfaceBandFollowsTerrainAndStaysInsideTheWorld() {
    VerticalMode mode = new VerticalMode.Surface(24, 24);

    // Sea level: a band of three sections around y=64.
    assertArrayEqualsInt(new int[] {2, 5}, mode.sections(64, -4, 19));
    // A mountain: the same thin band, just higher — this is why it follows rather than being fixed.
    assertArrayEqualsInt(new int[] {11, 14}, mode.sections(200, -4, 19));
    // Bedrock floor: clamped, never below the world.
    assertArrayEqualsInt(new int[] {-4, -3}, mode.sections(-60, -4, 19));
  }

  @Test
  void fullCapturesTheWholeColumn() {
    assertArrayEqualsInt(new int[] {-4, 19}, new VerticalMode.Full().sections(64, -4, 19));
  }

  private static void assertArrayEqualsInt(int[] expected, int[] actual) {
    assertEquals(java.util.Arrays.toString(expected), java.util.Arrays.toString(actual));
  }
}
