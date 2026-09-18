/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.cobblestonemc.minecraft.MinecraftBlock;
import org.spongepowered.api.block.BlockState;
import org.spongepowered.api.block.BlockTypes;

/**
 * Turns a saved chunk's palette entries into {@link MinecraftBlock}s.
 *
 * <p>This is the hinge that lets Cobblestone read chunks off disk without touching the server's
 * internals. A palette entry is a block id and its properties — {@code {Name: "minecraft:oak_door",
 * Properties: {open: "true", …}}} — which is exactly the block state string Sponge itself parses,
 * so the identity of a block never has to be worked out from Minecraft's own registry. Once it is a
 * Sponge {@link BlockState}, {@link SpongeBlock} answers every trait, meaning an offline chunk and
 * a loaded one are read by the same table and cannot disagree.
 *
 * <p>Results are memoized by state string. A palette holds tens of distinct blocks, a world holds
 * thousands, and a search reads tens of millions of cells, so parsing is paid once per distinct
 * block for the life of the server rather than once per section.
 */
final class SpongeBlocks implements PaletteResolver {

  /**
   * Parsed blocks by state string.
   *
   * <p>Unbounded on purpose: it is keyed by distinct block state, of which Minecraft has some tens
   * of thousands in total and a given world far fewer, so this settles at a few thousand entries
   * and stops growing. Bounding it would add eviction to the hottest read in the system to reclaim
   * kilobytes.
   */
  private final Map<String, MinecraftBlock> byState = new ConcurrentHashMap<>();

  /**
   * Air, the stand-in for a section with no recorded blocks.
   *
   * <p>Resolved on first use rather than at class load: Sponge's block registry does not exist
   * until the game is constructed, and a constant would tie merely mentioning this class to a
   * running server.
   */
  private volatile MinecraftBlock air;

  /**
   * Returns the block a palette entry describes.
   *
   * @param entry the palette entry, with {@code Name} and optional {@code Properties}
   * @return the block
   * @throws IOException if the entry names no block, or names one Sponge does not recognize
   */
  @Override
  public MinecraftBlock resolve(Map<String, Object> entry) throws IOException {
    String name = Nbt.string(entry, "Name");
    if (name == null) {
      throw new IOException("Palette entry has no block name");
    }
    String state = stateString(name, Nbt.compound(entry, "Properties"));
    MinecraftBlock cached = byState.get(state);
    if (cached != null) {
      return cached;
    }
    MinecraftBlock block;
    try {
      block = new SpongeBlock(BlockState.fromString(state));
    } catch (RuntimeException error) {
      // An id this server has no block for — a world that visited a mod this server is missing.
      // There is no honest answer, so the chunk is failed and obtained the ordinary way instead.
      throw new IOException("Cannot resolve block state '" + state + "'", error);
    }
    byState.put(state, block);
    return block;
  }

  @Override
  public MinecraftBlock air() {
    MinecraftBlock resolved = air;
    if (resolved == null) {
      // A race here costs a second identical instance, never a wrong one.
      resolved = new SpongeBlock(BlockTypes.AIR.get().defaultState());
      air = resolved;
    }
    return resolved;
  }

  /**
   * Rebuilds the {@code namespace:id[key=value,…]} form Sponge parses.
   *
   * <p>Properties are written in whatever order the file holds them, which is the order Minecraft
   * wrote them and therefore stable for a given block — so two entries for the same state produce
   * the same key, and the cache does not hold duplicates that differ only by ordering.
   */
  private static String stateString(String name, Map<String, Object> properties) {
    if (properties == null || properties.isEmpty()) {
      return name;
    }
    StringBuilder out = new StringBuilder(name.length() + properties.size() * 12);
    out.append(name).append('[');
    boolean first = true;
    for (Map.Entry<String, Object> property : properties.entrySet()) {
      if (!first) {
        out.append(',');
      }
      first = false;
      out.append(property.getKey()).append('=').append(property.getValue());
    }
    return out.append(']').toString();
  }
}
