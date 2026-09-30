/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.format;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A trait table written against different trait bits must be refused, not read.
 *
 * <p>The failure this prevents is silent. Adding a bit changes what every existing row means
 * without changing how any of them parse, so an old table reads cleanly and answers wrongly — soul
 * sand that does not know it is soul sand, and a coarse layer that prices a whole biome as ordinary
 * ground. Nothing downstream can notice that.
 */
class TraitTableVersionTest {

  @Test
  void aTableFromDifferentTraitBitsIsRefused(@TempDir Path dir) throws Exception {
    Path file = dir.resolve(TraitTable.FILE_NAME);
    Files.write(
        file,
        List.of(
            "#stonebrick-traits 0",
            "#state\tbits\tbreakTimeSeconds\tspeedFactor\tdamagePerSecond",
            "minecraft:stone\t0x2\t1.5\t1.0\t0.0"));

    Exception thrown = assertThrows(Exception.class, () -> TraitTable.read(file));

    // The message has to say what to do about it: the only cure is regenerating the corpus, and a
    // reader who is not told that has no way to guess.
    assertTrue(thrown.getMessage().contains("captureCorpus"), thrown.getMessage());
  }

  @Test
  void aTableThisBuildWroteIsReadBack(@TempDir Path dir) throws Exception {
    Path file = dir.resolve(TraitTable.FILE_NAME);
    TraitTable.builder()
        .add("minecraft:soul_sand", BlockTraits.builder().set(BlockTraits.SOUL_SAND, true).build())
        .build()
        .write(file);

    TraitTable read = TraitTable.read(file);

    assertTrue(read.asMap().get("minecraft:soul_sand").has(BlockTraits.SOUL_SAND));
    assertEquals(1, read.asMap().size());
  }
}
