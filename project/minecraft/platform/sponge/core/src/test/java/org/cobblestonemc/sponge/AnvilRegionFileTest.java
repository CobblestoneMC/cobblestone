/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests reading a chunk back out of a region file laid out the way Minecraft writes one. */
class AnvilRegionFileTest {

  private static final int SECTOR_BYTES = 4096;

  @TempDir Path folder;

  /**
   * Writes a region file holding one chunk, built to the Anvil specification: two 4 KiB header
   * tables, then the chunk's length, compression id and compressed payload, padded to a sector.
   */
  private Path writeRegion(int chunkX, int chunkZ, byte[] nbt, int compression) throws IOException {
    byte[] payload = compress(nbt, compression);
    Path file = folder.resolve("r." + (chunkX >> 5) + "." + (chunkZ >> 5) + ".mca");

    int dataSectors = (payload.length + 5 + SECTOR_BYTES - 1) / SECTOR_BYTES;
    byte[] region = new byte[SECTOR_BYTES * (2 + dataSectors)];
    ByteBuffer buffer = ByteBuffer.wrap(region);

    // Header: this chunk starts at sector 2 and spans dataSectors sectors.
    int index = ((chunkX & 31) + (chunkZ & 31) * 32) * 4;
    buffer.putInt(index, (2 << 8) | dataSectors);

    buffer.position(SECTOR_BYTES * 2);
    buffer.putInt(payload.length + 1);
    buffer.put((byte) compression);
    buffer.put(payload);

    Files.write(file, region);
    return file;
  }

  private static byte[] compress(byte[] raw, int compression) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    switch (compression) {
      case 1 -> {
        try (GZIPOutputStream out = new GZIPOutputStream(bytes)) {
          out.write(raw);
        }
      }
      case 2 -> {
        try (DeflaterOutputStream out = new DeflaterOutputStream(bytes)) {
          out.write(raw);
        }
      }
      default -> bytes.write(raw);
    }
    return bytes.toByteArray();
  }

  private static byte[] sampleChunk() throws IOException {
    Map<String, Object> blockStates = new LinkedHashMap<>();
    Map<String, Object> stone = new LinkedHashMap<>();
    stone.put("Name", "minecraft:stone");
    blockStates.put("palette", List.of(stone));

    Map<String, Object> section = new LinkedHashMap<>();
    section.put("Y", (byte) 0);
    section.put("block_states", blockStates);

    Map<String, Object> tag = new LinkedHashMap<>();
    tag.put("DataVersion", 3955);
    tag.put("Status", "minecraft:full");
    tag.put("xPos", 5);
    tag.put("sections", List.of(section));
    return NbtWriter.write(tag);
  }

  @Test
  void readsAZlibChunk() throws IOException {
    writeRegion(5, 9, sampleChunk(), 2);

    Map<String, Object> tag = AnvilRegionFile.readChunk(folder, 5, 9);

    assertNotNull(tag);
    assertEquals(3955, Nbt.integer(tag, "DataVersion", 0));
    assertEquals("minecraft:full", Nbt.string(tag, "Status"));
    assertEquals(5, Nbt.integer(tag, "xPos", 0));
    assertEquals(1, Nbt.list(tag, "sections").size());
  }

  @Test
  void readsAGzipChunk() throws IOException {
    writeRegion(0, 0, sampleChunk(), 1);

    assertNotNull(AnvilRegionFile.readChunk(folder, 0, 0));
  }

  @Test
  void readsAnUncompressedChunk() throws IOException {
    writeRegion(31, 31, sampleChunk(), 3);

    assertNotNull(AnvilRegionFile.readChunk(folder, 31, 31));
  }

  @Test
  void findsAChunkByItsPositionWithinTheRegion() throws IOException {
    // Chunk (33, 65) lives in region (1, 2) at local (1, 1) — the indexing most likely to be wrong.
    writeRegion(33, 65, sampleChunk(), 2);

    assertNotNull(AnvilRegionFile.readChunk(folder, 33, 65));
    assertNull(AnvilRegionFile.readChunk(folder, 34, 65), "a neighbour was never written");
  }

  @Test
  void answersNothingWhenTheRegionFileDoesNotExist() throws IOException {
    assertNull(AnvilRegionFile.readChunk(folder, 100, 100));
  }

  @Test
  void answersNothingForAChunkTheRegionNeverSaved() throws IOException {
    writeRegion(5, 9, sampleChunk(), 2);

    assertNull(AnvilRegionFile.readChunk(folder, 6, 9));
  }

  @Test
  void refusesACompressionItCannotRead() throws IOException {
    writeRegion(5, 9, sampleChunk(), 4); // LZ4, which Cobblestone declines rather than guesses at

    IOException error =
        assertThrows(IOException.class, () -> AnvilRegionFile.readChunk(folder, 5, 9));
    assertEquals(true, error.getMessage().contains("which Cobblestone cannot read"));
  }

  @Test
  void failsOnATornPayloadRatherThanReturningNonsense() throws IOException {
    Path file = writeRegion(5, 9, sampleChunk(), 2);
    byte[] region = Files.readAllBytes(file);
    // Scribble over the middle of the compressed payload, as a half-written save would.
    for (int i = SECTOR_BYTES * 2 + 12; i < SECTOR_BYTES * 2 + 40; i++) {
      region[i] = (byte) 0xFF;
    }
    Files.write(file, region);

    assertThrows(IOException.class, () -> AnvilRegionFile.readChunk(folder, 5, 9));
  }
}
