/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.cobblestonemc.minecraft.MinecraftBlock;
import org.cobblestonemc.minecraft.MinecraftChunk;
import org.cobblestonemc.minecraft.UnknownBlock;
import org.jetbrains.annotations.Nullable;

/**
 * A {@link MinecraftChunk} decoded from a saved chunk's NBT.
 *
 * <p>Each section keeps the two things the file gives it: the palette of distinct blocks, already
 * resolved to {@link MinecraftBlock}s, and the packed indices into it. That is the same shape the
 * file uses and roughly the same size — a few hundred longs per section rather than 4096 objects —
 * so holding a chunk costs about what holding its bytes would, and a block read is a shift and a
 * mask.
 *
 * <p>Instances are immutable and safe to read from any thread.
 */
final class AnvilChunk implements MinecraftChunk {

  /** The data version at which the {@code sections}/{@code block_states} layout arrived (1.18). */
  private static final int MIN_SUPPORTED_DATA_VERSION = 2860;

  /** Blocks per section edge, and the index stride the packed data uses. */
  private static final int SECTION_SIZE = 16;

  private final Section[] sections;
  private final int minSectionY;

  /** What a section the file never wrote is made of; see {@link PaletteResolver#air()}. */
  private final MinecraftBlock air;

  private AnvilChunk(Section[] sections, int minSectionY, MinecraftBlock air) {
    this.sections = sections;
    this.minSectionY = minSectionY;
    this.air = air;
  }

  /**
   * Decodes a saved chunk.
   *
   * <p>Answers {@code null} for a chunk that is saved but has nothing to say about blocks yet —
   * terrain that was never finished, so what is saved is not what a player would walk on.
   *
   * <p>A chunk written by a Minecraft too old for this layout is different: its blocks are real,
   * and only Cobblestone cannot read them, having no data fixers of its own. That throws {@link
   * UnsupportedChunkVersionException} so the caller gets the chunk some other way instead.
   *
   * @param tag the chunk's root tag
   * @param blocks how to turn a palette entry into a block
   * @return the decoded chunk, or {@code null} if it holds no usable block data
   * @throws UnsupportedChunkVersionException if the chunk was saved before the 1.18 layout
   * @throws IOException if the tag is present but malformed
   */
  static @Nullable AnvilChunk decode(Map<String, Object> tag, PaletteResolver blocks)
      throws IOException {
    int dataVersion = Nbt.integer(tag, "DataVersion", 0);
    if (dataVersion < MIN_SUPPORTED_DATA_VERSION) {
      // An un-upgraded chunk from before 1.18; only the server's data fixers can read it.
      throw new UnsupportedChunkVersionException(dataVersion);
    }
    String status = Nbt.string(tag, "Status");
    if (status == null || !(status.equals("minecraft:full") || status.equals("full"))) {
      // Terrain isn't finished, so what is saved is not what a player would walk on.
      return null;
    }

    List<Object> sectionTags = Nbt.list(tag, "sections");
    if (sectionTags.isEmpty()) {
      return null;
    }

    // The file does not record the world's height; the sections it carries define it.
    int minSectionY = Integer.MAX_VALUE;
    int maxSectionY = Integer.MIN_VALUE;
    for (Object element : sectionTags) {
      if (element instanceof Map<?, ?> section) {
        @SuppressWarnings("unchecked")
        int y = Nbt.integer((Map<String, Object>) section, "Y", Integer.MIN_VALUE);
        if (y != Integer.MIN_VALUE) {
          minSectionY = Math.min(minSectionY, y);
          maxSectionY = Math.max(maxSectionY, y);
        }
      }
    }
    if (minSectionY > maxSectionY) {
      throw new IOException("Chunk has sections but none of them say where");
    }

    Section[] sections = new Section[maxSectionY - minSectionY + 1];
    for (Object element : sectionTags) {
      if (!(element instanceof Map<?, ?> raw)) {
        continue;
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> sectionTag = (Map<String, Object>) raw;
      int y = Nbt.integer(sectionTag, "Y", Integer.MIN_VALUE);
      if (y == Integer.MIN_VALUE) {
        continue;
      }
      Map<String, Object> blockStates = Nbt.compound(sectionTag, "block_states");
      if (blockStates == null) {
        continue; // left null: a section with no blocks recorded is air
      }
      sections[y - minSectionY] = Section.decode(blockStates, blocks);
    }
    return new AnvilChunk(sections, minSectionY, blocks.air());
  }

  @Override
  public MinecraftBlock block(int localX, int y, int localZ) {
    int index = (y >> 4) - minSectionY;
    if (index < 0 || index >= sections.length) {
      return UnknownBlock.INSTANCE; // outside the height this chunk recorded
    }
    Section section = sections[index];
    if (section == null) {
      return air;
    }
    return section.block(localX & 15, y & 15, localZ & 15);
  }

  /** One 16&times;16&times;16 section: a resolved palette, and packed indices into it. */
  private static final class Section {

    private final MinecraftBlock[] palette;

    /** Packed palette indices, or {@code null} when the section is one block throughout. */
    private final long @Nullable [] data;

    private final int bitsPerEntry;
    private final int entriesPerLong;
    private final long mask;

    private Section(MinecraftBlock[] palette, long @Nullable [] data, int bitsPerEntry) {
      this.palette = palette;
      this.data = data;
      this.bitsPerEntry = bitsPerEntry;
      this.entriesPerLong = bitsPerEntry == 0 ? 0 : 64 / bitsPerEntry;
      this.mask = bitsPerEntry == 0 ? 0 : (1L << bitsPerEntry) - 1;
    }

    static Section decode(Map<String, Object> blockStates, PaletteResolver blocks)
        throws IOException {
      List<Object> paletteTags = Nbt.list(blockStates, "palette");
      if (paletteTags.isEmpty()) {
        throw new IOException("Section has block states but an empty palette");
      }
      MinecraftBlock[] palette = new MinecraftBlock[paletteTags.size()];
      for (int i = 0; i < palette.length; i++) {
        if (!(paletteTags.get(i) instanceof Map<?, ?> raw)) {
          throw new IOException("Palette entry " + i + " is not a compound");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> entry = (Map<String, Object>) raw;
        palette[i] = blocks.resolve(entry);
      }

      long[] data = Nbt.longArray(blockStates, "data");
      if (data == null) {
        // No indices at all: the section is a single block, which is how air is stored.
        return new Section(palette, null, 0);
      }

      // Indices are as wide as the palette needs, never narrower than four bits, and — since 1.16 —
      // never split across two longs, so the spare high bits of each long are simply unused.
      int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(palette.length - 1));
      if (palette.length == 1) {
        bits = 4;
      }
      int entriesPerLong = 64 / bits;
      int required =
          (SECTION_SIZE * SECTION_SIZE * SECTION_SIZE + entriesPerLong - 1) / entriesPerLong;
      if (data.length < required) {
        throw new IOException(
            "Section data holds " + data.length + " longs where " + required + " are needed");
      }
      return new Section(palette, data, bits);
    }

    MinecraftBlock block(int x, int y, int z) {
      if (data == null) {
        return palette[0];
      }
      int index = (y * SECTION_SIZE + z) * SECTION_SIZE + x;
      int slot = index / entriesPerLong;
      int offset = (index % entriesPerLong) * bitsPerEntry;
      int id = (int) ((data[slot] >>> offset) & mask);
      // A palette index out of range means the file disagrees with itself; treat that cell as
      // unknowable rather than failing the whole chunk a search is already reading.
      return id >= 0 && id < palette.length ? palette[id] : UnknownBlock.INSTANCE;
    }
  }
}
