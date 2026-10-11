/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.copier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.cobblestonemc.stonebrick.format.BlockPalette;
import org.cobblestonemc.stonebrick.format.CaptureDirectory;
import org.cobblestonemc.stonebrick.format.ChunkColumn;
import org.cobblestonemc.stonebrick.format.ChunkColumnCodec;
import org.cobblestonemc.stonebrick.format.TraitTable;

/**
 * Rebuilds a capture's trait table from the block states its columns actually reference.
 *
 * <p>This is what makes a change to trait logic cheap. Captured block data is frozen — it is a
 * record of what the world contained — while traits are derived from code that changes. When {@code
 * PaperBlock} learns that some block was misclassified, the fix is to re-run this on a server of
 * the captured Minecraft version; not one column file is touched.
 *
 * <p>It walks the capture rather than the block registry on purpose. The registry holds tens of
 * thousands of states, nearly all of which no capture contains, and a table of those would be a
 * large file whose diffs were dominated by blocks nothing reads.
 */
final class TraitDump {

  private TraitDump() {}

  /**
   * Regenerates the trait table for every block state the capture's columns reference.
   *
   * <p>Must run on the server thread: {@code createBlockData} parses against the live registry.
   *
   * @param capture the capture directory
   * @return the number of block states described
   * @throws IOException if the capture cannot be read or the table cannot be written
   */
  static int writeAll(CaptureDirectory capture) throws IOException {
    TraitTable.Builder traits = TraitTable.builder();
    traits.add(
        org.cobblestonemc.stonebrick.format.Sbc.AIR,
        TraitCapture.of(Material.AIR.createBlockData()));

    for (String world : capture.worlds()) {
      for (Path column : capture.listColumns(world)) {
        ChunkColumn decoded = ChunkColumnCodec.read(column);
        BlockPalette palette = decoded.palette();
        for (String state : palette.states()) {
          if (traits.contains(state)) {
            continue;
          }
          traits.add(state, TraitCapture.of(parse(state)));
        }
      }
    }
    TraitTable table = traits.build();
    table.write(capture.traitTable());
    return table.size();
  }

  /**
   * Parses a captured block state string back into live block data.
   *
   * <p>A state this server cannot parse is a hard failure, not a skip: it means the capture was
   * taken on a different Minecraft version, and a table silently missing that state would make
   * every block of that kind unreadable at benchmark time with no hint as to why.
   */
  private static BlockData parse(String state) throws IOException {
    try {
      return Bukkit.createBlockData(state);
    } catch (IllegalArgumentException e) {
      throw new IOException(
          "this server cannot parse the block state '"
              + state
              + "'. The capture was taken on a different Minecraft version"
              + " ("
              + Bukkit.getMinecraftVersion()
              + " here); regenerate traits on a matching server.",
          e);
    }
  }

  /**
   * Returns the capture directories under a root, for tooling that wants to offer a choice.
   *
   * @param root the directory holding captures
   * @return the capture names
   * @throws IOException if the directory cannot be listed
   */
  static List<String> captureNames(Path root) throws IOException {
    if (!Files.isDirectory(root)) {
      return List.of();
    }
    try (var entries = Files.list(root)) {
      return entries
          .filter(Files::isDirectory)
          .map(path -> path.getFileName().toString())
          .sorted()
          .toList();
    }
  }
}
