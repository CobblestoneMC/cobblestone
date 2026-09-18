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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.cobblestonemc.minecraft.MinecraftBlock;
import org.cobblestonemc.minecraft.UnknownBlock;
import org.junit.jupiter.api.Test;

/**
 * Tests the decoding of a saved chunk, which is the part of the offline read that has to be right
 * on its own: everything else either works or throws, but a mistake in the bit unpacking silently
 * returns the wrong block, and a search would quietly path through a wall.
 *
 * <p>Palette entries resolve to a stub keyed by block name, so nothing here needs a running Sponge.
 */
class AnvilChunkTest {

  /** Resolves each palette entry to a distinct marker block named after the entry. */
  private static final class NamedBlock implements MinecraftBlock {
    final String name;

    NamedBlock(String name) {
      this.name = name;
    }

    @Override
    public boolean isPassable() {
      return false;
    }

    @Override
    public boolean isSolidTop() {
      return true;
    }
  }

  private final Map<String, MinecraftBlock> stubs = new LinkedHashMap<>();

  private final PaletteResolver resolver =
      new PaletteResolver() {
        @Override
        public MinecraftBlock resolve(Map<String, Object> entry) {
          return stubs.computeIfAbsent(Nbt.string(entry, "Name"), NamedBlock::new);
        }

        @Override
        public MinecraftBlock air() {
          return stubs.computeIfAbsent("air", NamedBlock::new);
        }
      };

  private static Map<String, Object> paletteEntry(String name) {
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("Name", name);
    return entry;
  }

  private static Map<String, Object> chunk(List<Object> sections) {
    Map<String, Object> tag = new LinkedHashMap<>();
    tag.put("DataVersion", 3955);
    tag.put("Status", "minecraft:full");
    tag.put("sections", sections);
    return tag;
  }

  private static Map<String, Object> section(int y, List<Object> palette, long[] data) {
    Map<String, Object> blockStates = new LinkedHashMap<>();
    blockStates.put("palette", palette);
    if (data != null) {
      blockStates.put("data", data);
    }
    Map<String, Object> section = new LinkedHashMap<>();
    section.put("Y", (byte) y);
    section.put("block_states", blockStates);
    return section;
  }

  private String nameAt(AnvilChunk decoded, int x, int y, int z) {
    MinecraftBlock block = decoded.block(x, y, z);
    return block instanceof NamedBlock named ? named.name : block.toString();
  }

  @Test
  void unpacksFourBitIndicesAtHandComputedPositions() throws IOException {
    // Four bits per entry means sixteen entries per long, low bits first, and the cell at
    // (x, y, z) is at index (y * 16 + z) * 16 + x. So this first long holds indices 1, 2 and 3 for
    // x = 0, 1, 2 and leaves the rest at 0; the second long starts the row z = 1 with index 3.
    long[] data = new long[256];
    data[0] = 0x321L;
    data[1] = 0x3L;

    List<Object> palette =
        List.of(
            paletteEntry("air"),
            paletteEntry("stone"),
            paletteEntry("dirt"),
            paletteEntry("grass"));
    AnvilChunk decoded =
        AnvilChunk.decode(chunk(new ArrayList<>(List.of(section(0, palette, data)))), resolver);

    assertNotNull(decoded);
    assertEquals("stone", nameAt(decoded, 0, 0, 0));
    assertEquals("dirt", nameAt(decoded, 1, 0, 0));
    assertEquals("grass", nameAt(decoded, 2, 0, 0));
    assertEquals("air", nameAt(decoded, 3, 0, 0));
    assertEquals("grass", nameAt(decoded, 0, 0, 1)); // index 16: first entry of the next long
    assertEquals("air", nameAt(decoded, 1, 0, 1));
  }

  @Test
  void readsFiveBitIndicesWhenThePaletteOutgrowsFour() throws IOException {
    // Seventeen entries need five bits, so twelve fit per long and the top four bits go unused —
    // the case a reader that packed entries across long boundaries would get wrong.
    List<Object> palette = new ArrayList<>();
    for (int i = 0; i < 17; i++) {
      palette.add(paletteEntry("block" + i));
    }
    long[] data = new long[(4096 + 11) / 12];
    // Put index 16 (the highest, needing all five bits) at cell index 11 — the last entry that
    // fits in the first long — and index 9 at cell index 12, the first of the second long.
    data[0] = 16L << (11 * 5);
    data[1] = 9L;

    AnvilChunk decoded =
        AnvilChunk.decode(chunk(new ArrayList<>(List.of(section(0, palette, data)))), resolver);

    assertNotNull(decoded);
    assertEquals("block16", nameAt(decoded, 11, 0, 0));
    assertEquals("block9", nameAt(decoded, 12, 0, 0));
    assertEquals("block0", nameAt(decoded, 10, 0, 0));
  }

  @Test
  void treatsASectionWithNoDataAsOneBlockThroughout() throws IOException {
    AnvilChunk decoded =
        AnvilChunk.decode(
            chunk(new ArrayList<>(List.of(section(0, List.of(paletteEntry("bedrock")), null)))),
            resolver);

    assertNotNull(decoded);
    assertEquals("bedrock", nameAt(decoded, 0, 0, 0));
    assertEquals("bedrock", nameAt(decoded, 15, 15, 15));
  }

  @Test
  void mapsSectionsToTheirWorldHeights() throws IOException {
    List<Object> low = List.of(paletteEntry("deepslate"));
    List<Object> high = List.of(paletteEntry("snow"));
    AnvilChunk decoded =
        AnvilChunk.decode(
            chunk(new ArrayList<>(List.of(section(-4, low, null), section(3, high, null)))),
            resolver);

    assertNotNull(decoded);
    assertEquals("deepslate", nameAt(decoded, 0, -64, 0)); // section -4 covers y -64..-49
    assertEquals("snow", nameAt(decoded, 0, 48, 0)); // section 3 covers y 48..63
    // The gap between them was never written, so it is air rather than an error.
    assertEquals("air", nameAt(decoded, 0, 0, 0));
  }

  @Test
  void answersUnknownOutsideTheHeightTheChunkRecorded() throws IOException {
    AnvilChunk decoded =
        AnvilChunk.decode(
            chunk(new ArrayList<>(List.of(section(0, List.of(paletteEntry("stone")), null)))),
            resolver);

    assertNotNull(decoded);
    assertSame(UnknownBlock.INSTANCE, decoded.block(0, 5000, 0));
    assertSame(UnknownBlock.INSTANCE, decoded.block(0, -5000, 0));
  }

  @Test
  void declinesChunksOlderThanTheLayoutItUnderstands() throws IOException {
    Map<String, Object> tag =
        chunk(new ArrayList<>(List.of(section(0, List.of(paletteEntry("stone")), null))));
    tag.put("DataVersion", 2000); // a 1.16 world that was never upgraded

    assertNull(AnvilChunk.decode(tag, resolver));
  }

  @Test
  void declinesChunksWhoseTerrainIsUnfinished() throws IOException {
    Map<String, Object> tag =
        chunk(new ArrayList<>(List.of(section(0, List.of(paletteEntry("stone")), null))));
    tag.put("Status", "minecraft:noise");

    assertNull(AnvilChunk.decode(tag, resolver));
  }

  @Test
  void rejectsASectionWhosePackedDataIsTooShort() {
    long[] truncated = new long[3];
    Map<String, Object> tag =
        chunk(
            new ArrayList<>(
                List.of(section(0, List.of(paletteEntry("a"), paletteEntry("b")), truncated))));

    assertThrows(IOException.class, () -> AnvilChunk.decode(tag, resolver));
  }
}
