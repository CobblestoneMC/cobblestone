/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.lod;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import org.jetbrains.annotations.Nullable;

/**
 * Built columns on disk, for one world: what lets a server start a search on terrain it built
 * before it restarted.
 *
 * <p><b>32×32 columns to a file,</b> as Minecraft groups its own chunks into region files: one file
 * per column would be millions of tiny files on a big world, and one file per world would have to
 * be rewritten whole. A file is a header and then one compressed record per column it holds.
 *
 * <pre>
 *   int    magic "CRG1"
 *   int    format version        layout of this file and of a column record
 *   int    classifier version    the rules the columns were built by
 *   double detour ratio          a fill setting that changes the regions
 *   int    world floor, int sections
 *   int    count, then count x { short index in the file, int length, deflated column }
 * </pre>
 *
 * <p><b>A file whose header does not match is empty.</b> Columns built under different rules or a
 * different world height are a different graph; they are ignored on read and replaced on the next
 * write, never mixed in.
 *
 * <p><b>Writes replace a file whole,</b> through a temporary file moved into place, so a crash
 * mid-write leaves the old file or the new one and never half of each. Reads only touch the records
 * asked for; each file's index of records is read once and kept.
 *
 * <p>Thread-safe by being synchronized. {@link RegionStore} calls it from one disk thread, so the
 * lock is never contended; it is there so that nothing else has to know that.
 */
public final class RegionFiles {

  private static final int MAGIC = 0x43524731; // "CRG1"

  /** The layout of a file and of a column record. Bump it with any change to either. */
  static final int FORMAT_VERSION = 3;

  /** Columns a file holds along each axis. */
  static final int SPAN = 32;

  private final Path directory;
  private final double detourRatio;
  private final int minY;
  private final int sections;

  /** Per file: each record's position and length, by index in the file. */
  private final Map<Long, Map<Integer, long[]>> indexes = new HashMap<>();

  private long recordsRead;
  private long bytesRead;
  private long bytesWritten;

  /**
   * Creates the store for one world.
   *
   * @param directory where this world's files go; created on the first write
   * @param minY the world's floor, rounded down to a section
   * @param sections how many sections the world is tall
   * @param detourRatio the fill's detour ratio, recorded so a change of it is not read back
   */
  public RegionFiles(Path directory, int minY, int sections, double detourRatio) {
    this.directory = directory;
    this.minY = minY;
    this.sections = sections;
    this.detourRatio = detourRatio;
  }

  /**
   * Returns a directory name for a world key, safe on any file system.
   *
   * @param worldKey a namespaced key, such as {@code minecraft:overworld}
   * @return the directory name
   */
  public static String directoryName(String worldKey) {
    return worldKey.replaceAll("[^A-Za-z0-9._-]", "_");
  }

  /**
   * Reads a column, or returns {@code null} if none is stored or the stored one is unreadable.
   *
   * @param chunkX the chunk X
   * @param chunkZ the chunk Z
   * @return the column, or {@code null}
   * @throws IOException if the file cannot be read at all
   */
  public synchronized @Nullable RegionColumn read(int chunkX, int chunkZ) throws IOException {
    long file = fileKey(chunkX, chunkZ);
    long[] entry = index(file).get(indexInFile(chunkX, chunkZ));
    if (entry == null) {
      return null;
    }
    byte[] blob = new byte[(int) entry[1]];
    RandomAccessFile in = open(file);
    in.seek(entry[0]);
    in.readFully(blob);
    recordsRead++;
    bytesRead += blob.length;
    RegionColumn column = decode(blob);
    if (column.chunkX != chunkX || column.chunkZ != chunkZ) {
      return null; // a record filed under the wrong column: treat it as missing and rebuild
    }
    return column;
  }

  /**
   * Writes columns and removes others, replacing each file touched.
   *
   * @param columns the columns to store, replacing any stored before
   * @param removed the columns to remove, packed as {@code (chunkX << 32) | chunkZ}
   * @throws IOException if a file cannot be written; files already written stay written
   */
  public synchronized void write(Collection<RegionColumn> columns, Collection<Long> removed)
      throws IOException {
    Map<Long, Map<Integer, RegionColumn>> byFile = new LinkedHashMap<>();
    for (RegionColumn column : columns) {
      byFile
          .computeIfAbsent(fileKey(column.chunkX, column.chunkZ), k -> new HashMap<>())
          .put(indexInFile(column.chunkX, column.chunkZ), column);
    }
    Map<Long, Set<Integer>> removedByFile = new HashMap<>();
    for (long packed : removed) {
      int chunkX = (int) (packed >> 32);
      int chunkZ = (int) packed;
      removedByFile
          .computeIfAbsent(fileKey(chunkX, chunkZ), k -> new HashSet<>())
          .add(indexInFile(chunkX, chunkZ));
      byFile.computeIfAbsent(fileKey(chunkX, chunkZ), k -> new HashMap<>());
    }
    for (Map.Entry<Long, Map<Integer, RegionColumn>> entry : byFile.entrySet()) {
      long file = entry.getKey();
      Map<Integer, byte[]> records = readAll(file);
      for (int index : removedByFile.getOrDefault(file, Set.of())) {
        records.remove(index);
      }
      for (Map.Entry<Integer, RegionColumn> column : entry.getValue().entrySet()) {
        records.put(column.getKey(), encode(column.getValue()));
      }
      writeFile(file, records);
    }
  }

  /**
   * Returns how many column records have been read.
   *
   * @return the record count
   */
  public synchronized long recordsRead() {
    return recordsRead;
  }

  /**
   * Returns how many compressed bytes have been read.
   *
   * @return the byte count
   */
  public synchronized long bytesRead() {
    return bytesRead;
  }

  /**
   * Returns how many bytes have been written.
   *
   * @return the byte count
   */
  public synchronized long bytesWritten() {
    return bytesWritten;
  }

  /**
   * Files kept open for reading, most recently used last.
   *
   * <p>A search reads the columns of a few files one after another, and opening a file per column
   * cost more than reading the column. A handful stay open; a file is closed before it is replaced.
   */
  private final LinkedHashMap<Long, RandomAccessFile> open = new LinkedHashMap<>(16, 0.75f, true);

  private static final int OPEN_FILES = 8;

  private RandomAccessFile open(long file) throws IOException {
    RandomAccessFile handle = open.get(file);
    if (handle != null) {
      return handle;
    }
    handle = new RandomAccessFile(path(file).toFile(), "r");
    open.put(file, handle);
    if (open.size() > OPEN_FILES) {
      Map.Entry<Long, RandomAccessFile> eldest = open.entrySet().iterator().next();
      open.remove(eldest.getKey());
      eldest.getValue().close();
    }
    return handle;
  }

  private void close(long file) throws IOException {
    RandomAccessFile handle = open.remove(file);
    if (handle != null) {
      handle.close();
    }
  }

  /**
   * Closes every file held open for reading.
   *
   * @throws IOException if one cannot be closed
   */
  public synchronized void close() throws IOException {
    for (RandomAccessFile handle : open.values()) {
      handle.close();
    }
    open.clear();
  }

  private Map<Integer, long[]> index(long file) throws IOException {
    Map<Integer, long[]> known = indexes.get(file);
    if (known != null) {
      return known;
    }
    Map<Integer, long[]> index = new HashMap<>();
    Path path = path(file);
    if (Files.exists(path)) {
      try (DataInputStream in =
          new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
        int count = header(in);
        long position = HEADER_BYTES;
        for (int i = 0; i < count; i++) {
          int at = in.readUnsignedShort();
          int length = in.readInt();
          position += 6;
          index.put(at, new long[] {position, length});
          skipFully(in, length);
          position += length;
        }
      } catch (EOFException truncated) {
        index.clear(); // a torn file is an empty one: it will be rebuilt, not half-trusted
      }
    }
    indexes.put(file, index);
    return index;
  }

  private Map<Integer, byte[]> readAll(long file) throws IOException {
    Map<Integer, byte[]> records = new HashMap<>();
    Path path = path(file);
    if (!Files.exists(path)) {
      return records;
    }
    try (DataInputStream in =
        new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
      int count = header(in);
      for (int i = 0; i < count; i++) {
        int at = in.readUnsignedShort();
        byte[] blob = new byte[in.readInt()];
        in.readFully(blob);
        records.put(at, blob);
      }
    } catch (EOFException truncated) {
      records.clear();
    }
    return records;
  }

  private void writeFile(long file, Map<Integer, byte[]> records) throws IOException {
    // Closed first: a file open for reading cannot be replaced on Windows, and its index is stale.
    close(file);
    Path path = path(file);
    if (records.isEmpty()) {
      Files.deleteIfExists(path);
      indexes.put(file, new HashMap<>());
      return;
    }
    Files.createDirectories(directory);
    Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
    Map<Integer, long[]> index = new HashMap<>();
    long position = HEADER_BYTES;
    try (DataOutputStream out =
        new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) {
      out.writeInt(MAGIC);
      out.writeInt(FORMAT_VERSION);
      out.writeInt(RegionColumnBuilder.CLASSIFIER_VERSION);
      out.writeDouble(detourRatio);
      out.writeInt(minY);
      out.writeInt(sections);
      out.writeInt(records.size());
      for (Map.Entry<Integer, byte[]> record : records.entrySet()) {
        out.writeShort(record.getKey());
        out.writeInt(record.getValue().length);
        position += 6;
        index.put(record.getKey(), new long[] {position, record.getValue().length});
        out.write(record.getValue());
        position += record.getValue().length;
      }
    }
    try {
      Files.move(
          temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException unsupported) {
      Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
    }
    bytesWritten += position;
    indexes.put(file, index);
  }

  /** Bytes before the first record: magic, two versions, the detour, floor, sections, count. */
  private static final int HEADER_BYTES = 4 + 4 + 4 + 8 + 4 + 4 + 4;

  /** Reads a header, returning how many records follow, or 0 if it is not one of ours. */
  private int header(DataInputStream in) throws IOException {
    if (in.readInt() != MAGIC
        || in.readInt() != FORMAT_VERSION
        || in.readInt() != RegionColumnBuilder.CLASSIFIER_VERSION
        || in.readDouble() != detourRatio
        || in.readInt() != minY
        || in.readInt() != sections) {
      return 0;
    }
    return in.readInt();
  }

  private static byte[] encode(RegionColumn column) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream(4096);
    Deflater deflater = new Deflater(Deflater.BEST_SPEED);
    try (DataOutputStream out =
        new DataOutputStream(new DeflaterOutputStream(bytes, deflater, 4096))) {
      column.write(out);
    } finally {
      deflater.end();
    }
    return bytes.toByteArray();
  }

  private static RegionColumn decode(byte[] blob) throws IOException {
    try (DataInputStream in =
        new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(blob)))) {
      return RegionColumn.read(in);
    }
  }

  private static void skipFully(InputStream in, long bytes) throws IOException {
    long left = bytes;
    while (left > 0) {
      long skipped = in.skip(left);
      if (skipped <= 0) {
        if (in.read() < 0) {
          throw new EOFException();
        }
        skipped = 1;
      }
      left -= skipped;
    }
  }

  private Path path(long file) {
    return directory.resolve("r." + (int) (file >> 32) + "." + (int) file + ".crg");
  }

  private static long fileKey(int chunkX, int chunkZ) {
    return ((long) Math.floorDiv(chunkX, SPAN) << 32)
        | (Math.floorDiv(chunkZ, SPAN) & 0xFFFF_FFFFL);
  }

  private static int indexInFile(int chunkX, int chunkZ) {
    return Math.floorMod(chunkX, SPAN) * SPAN + Math.floorMod(chunkZ, SPAN);
  }
}
