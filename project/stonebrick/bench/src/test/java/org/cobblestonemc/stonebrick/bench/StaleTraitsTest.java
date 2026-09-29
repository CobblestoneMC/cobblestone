/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.bench;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.cobblestonemc.stonebrick.format.BlockTraits;
import org.cobblestonemc.stonebrick.format.CaptureDirectory;
import org.cobblestonemc.stonebrick.format.TraitTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Terrain being complete is not the same as the corpus being usable.
 *
 * <p>A change to the trait bits leaves every column file on disk exactly as valid as it was and
 * every description of those columns wrong. The provisioner has to ask for a capture anyway, or a
 * developer who changes a medium gets "Corpus is complete; nothing to capture" and then silently
 * wrong terrain for as long as they keep that directory.
 */
class StaleTraitsTest {

  private static CorpusProvisioner provisionerAt(Path corpus) {
    // Nothing here boots a server; only the staleness check is under test.
    return new CorpusProvisioner(
        corpus, corpus.resolve("server"), Path.of("unused.jar"), false, "java");
  }

  @Test
  void aTableFromDifferentTraitBitsCountsAsStale(@TempDir Path dir) throws Exception {
    CaptureDirectory capture = CaptureDirectory.at(dir.resolve("capture"));
    Files.createDirectories(capture.root());
    Files.write(capture.traitTable(), List.of("#stonebrick-traits 0", "#state\tbits"));

    assertTrue(provisionerAt(dir).staleTraits(capture));
  }

  @Test
  void aMissingTableCountsAsStale(@TempDir Path dir) throws Exception {
    CaptureDirectory capture = CaptureDirectory.at(dir.resolve("capture"));
    Files.createDirectories(capture.root());

    assertTrue(provisionerAt(dir).staleTraits(capture));
  }

  @Test
  void aTableThisBuildWroteIsNotStale(@TempDir Path dir) throws Exception {
    CaptureDirectory capture = CaptureDirectory.at(dir.resolve("capture"));
    Files.createDirectories(capture.root());
    TraitTable.builder()
        .add("minecraft:stone", BlockTraits.builder().build())
        .build()
        .write(capture.traitTable());

    assertFalse(provisionerAt(dir).staleTraits(capture));
  }
}
