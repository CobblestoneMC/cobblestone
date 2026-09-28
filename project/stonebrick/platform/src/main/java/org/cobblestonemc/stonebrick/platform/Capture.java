/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.cobblestonemc.minecraft.MinecraftWorld;
import org.cobblestonemc.stonebrick.format.CaptureDirectory;
import org.cobblestonemc.stonebrick.format.CaptureFormatException;
import org.cobblestonemc.stonebrick.format.ChunkColumn;
import org.cobblestonemc.stonebrick.format.ChunkColumnCodec;
import org.cobblestonemc.stonebrick.format.TraitTable;
import org.jetbrains.annotations.Nullable;

/**
 * A capture loaded and ready to be searched.
 *
 * <p><b>Column bytes are held in memory, decoded on every fetch.</b> Both halves of that are
 * deliberate. Holding the bytes takes the real disk out of the measurement loop, so the only IO
 * cost in a benchmark is the one the {@code IoProfile} injects — which is the point of having a
 * capture format at all. Decoding per fetch rather than caching decoded chunks mirrors what a live
 * server does, where every offline chunk read pays its own decode, and leaves the per-solve chunk
 * cache to do the caching it is there to do.
 *
 * <p>A corpus capture is a few tens of megabytes of raw columns, so holding one is unremarkable;
 * the largest scenarios are the ones excluded from version control anyway.
 */
public final class Capture {

  private final String name;
  private final CaptureDirectory directory;
  private final TraitTable traits;
  private final MissingCaptureLog missing = new MissingCaptureLog();
  private final Map<String, WorldData> worlds = new HashMap<>();

  private Capture(String name, CaptureDirectory directory, TraitTable traits) {
    this.name = name;
    this.directory = directory;
    this.traits = traits;
  }

  /** One world's captured columns and the bounds it was captured from. */
  private record WorldData(Map<Long, byte[]> columns, int minY, int maxY, String environment) {}

  /**
   * Loads a capture from disk.
   *
   * @param root the capture directory
   * @return the loaded capture
   * @throws IOException if the capture is missing, unreadable, or internally inconsistent
   */
  public static Capture load(Path root) throws IOException {
    CaptureDirectory directory = CaptureDirectory.at(root);
    if (!Files.isDirectory(root)) {
      throw new CaptureFormatException("no capture at " + root);
    }
    if (!Files.exists(directory.traitTable())) {
      throw new CaptureFormatException(
          root
              + " has no "
              + org.cobblestonemc.stonebrick.format.TraitTable.FILE_NAME
              + "; run /copier traits on a server of the captured Minecraft version");
    }
    Capture capture =
        new Capture(
            root.getFileName() == null ? root.toString() : root.getFileName().toString(),
            directory,
            TraitTable.read(directory.traitTable()));
    Map<String, String> meta = directory.readMeta();
    for (String world : directory.worlds()) {
      capture.loadWorld(world, meta);
    }
    return capture;
  }

  private void loadWorld(String worldKey, Map<String, String> meta) throws IOException {
    Map<Long, byte[]> columns = new HashMap<>();
    List<Path> files = directory.listColumns(worldKey);
    for (Path file : files) {
      int[] coordinates = CaptureDirectory.parseColumnName(file.getFileName().toString());
      columns.put(key(coordinates[0], coordinates[1]), Files.readAllBytes(file));
    }
    worlds.put(
        worldKey,
        new WorldData(
            columns,
            intMeta(meta, "world." + worldKey + ".minY", -64),
            intMeta(meta, "world." + worldKey + ".maxY", 319),
            meta.getOrDefault("world." + worldKey + ".environment", "NORMAL")));
  }

  private static int intMeta(Map<String, String> meta, String key, int fallback) {
    String value = meta.get(key);
    if (value == null) {
      return fallback;
    }
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  /**
   * Returns the capture's name, used in the message that reports uncaptured reads.
   *
   * @return the capture name
   */
  public String name() {
    return name;
  }

  /**
   * Returns the capture's directory.
   *
   * @return the root
   */
  public Path root() {
    return directory.root();
  }

  /**
   * Returns the log of reads that fell outside this capture.
   *
   * <p>Owned by the capture rather than by a platform, so that a tool reading chunks directly is
   * held to the same standard as a search: a read past the edge is recorded either way.
   *
   * @return the missing-capture log
   */
  public MissingCaptureLog missing() {
    return missing;
  }

  /**
   * Returns the trait table.
   *
   * @return the traits
   */
  public TraitTable traits() {
    return traits;
  }

  /**
   * Returns the world keys this capture holds.
   *
   * @return the world keys
   */
  public java.util.Set<String> worldKeys() {
    return worlds.keySet();
  }

  /**
   * Returns how many columns were captured for a world.
   *
   * @param worldKey the world key
   * @return the column count, or zero if the world is not in this capture
   */
  public int columnCount(String worldKey) {
    WorldData data = worlds.get(worldKey);
    return data == null ? 0 : data.columns().size();
  }

  /**
   * Returns a world's lowest block Y as the capture recorded it.
   *
   * @param worldKey the world key
   * @return the minimum Y
   */
  public int minY(String worldKey) {
    WorldData data = worlds.get(worldKey);
    return data == null ? -64 : data.minY();
  }

  /**
   * Returns a world's highest block Y as the capture recorded it.
   *
   * @param worldKey the world key
   * @return the maximum Y
   */
  public int maxY(String worldKey) {
    WorldData data = worlds.get(worldKey);
    return data == null ? 319 : data.maxY();
  }

  /**
   * Returns a world's environment as the capture recorded it.
   *
   * @param worldKey the world key
   * @return the environment
   */
  public MinecraftWorld.Environment environment(String worldKey) {
    WorldData data = worlds.get(worldKey);
    if (data == null) {
      return MinecraftWorld.Environment.CUSTOM;
    }
    return switch (data.environment()) {
      case "NORMAL" -> MinecraftWorld.Environment.OVERWORLD;
      case "NETHER" -> MinecraftWorld.Environment.NETHER;
      case "THE_END" -> MinecraftWorld.Environment.END;
      default -> MinecraftWorld.Environment.CUSTOM;
    };
  }

  /**
   * Decodes the column at the given chunk coordinates, or returns {@code null} if the capture does
   * not contain it.
   *
   * @param worldKey the world key
   * @param chunkX the chunk X
   * @param chunkZ the chunk Z
   * @return the column, or {@code null}
   * @throws CaptureFormatException if the stored bytes will not decode
   */
  public @Nullable ChunkColumn column(String worldKey, int chunkX, int chunkZ)
      throws CaptureFormatException {
    WorldData data = worlds.get(worldKey);
    if (data == null) {
      return null;
    }
    byte[] bytes = data.columns().get(key(chunkX, chunkZ));
    return bytes == null ? null : ChunkColumnCodec.read(bytes);
  }

  /**
   * Returns the stored size of a column in bytes, or {@code -1} if the capture does not contain it.
   *
   * <p>Answered without decoding, because the IO model needs the size to charge a throughput cost
   * <em>before</em> the read completes — and because "is this captured at all" is the question
   * asked most often and should not cost a decode.
   *
   * @param worldKey the world key
   * @param chunkX the chunk X
   * @param chunkZ the chunk Z
   * @return the stored byte count, or {@code -1}
   */
  public int storedBytes(String worldKey, int chunkX, int chunkZ) {
    WorldData data = worlds.get(worldKey);
    if (data == null) {
      return -1;
    }
    byte[] bytes = data.columns().get(key(chunkX, chunkZ));
    return bytes == null ? -1 : bytes.length;
  }

  private static long key(int chunkX, int chunkZ) {
    return ((long) chunkX << 32) | (chunkZ & 0xFFFF_FFFFL);
  }
}
