/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import org.jetbrains.annotations.Nullable;

/**
 * Pulls one chunk's saved NBT out of a region (<i>.mca</i>) file.
 *
 * <p>The Anvil layout is simple and has not moved since 1.13. A region file holds a 32&times;32
 * block of chunks behind two 4 KiB header tables: the first gives each chunk a sector offset and
 * length, the second a timestamp nothing here reads. A chunk's payload is a four-byte length, a
 * one-byte compression id, and the compressed NBT. Chunks too large for the 255-sector limit live
 * beside the region file in their own {@code c.x.z.mcc}, flagged by the high bit of that id.
 *
 * <p><b>Files are opened per read rather than kept.</b> A region covers 1024 chunks, so caching
 * handles would save real work — but the server owns these files and rewrites them as chunks save,
 * and a cached handle would then be pointed at a layout that has moved underneath it. Opening,
 * seeking twice and closing costs microseconds against a disk read that costs far more, and it
 * leaves nothing to go stale, leak, or hold a file open on Windows while the server wants it.
 *
 * <p>Reads are not synchronized against the server's writes, and cannot be. A chunk caught
 * mid-write decompresses to nonsense and fails, which is the same answer as any other unreadable
 * chunk: the caller falls back to loading it properly. Corruption is therefore a slow path, never a
 * wrong answer.
 */
final class AnvilRegionFile {

  private static final int SECTOR_BYTES = 4096;
  private static final int HEADER_ENTRY_BYTES = 4;

  private static final int COMPRESSION_GZIP = 1;
  private static final int COMPRESSION_ZLIB = 2;
  private static final int COMPRESSION_NONE = 3;

  /** Set in the compression byte when the payload was too big and lives in its own file. */
  private static final int EXTERNAL_FLAG = 0x80;

  /** A sanity bound on a chunk payload, so a damaged length cannot ask for the heap. */
  private static final int MAX_PAYLOAD_BYTES = 64 * 1024 * 1024;

  private AnvilRegionFile() {}

  /**
   * Reads the saved NBT of one chunk.
   *
   * @param regionFolder the world's {@code region} directory
   * @param chunkX the chunk X
   * @param chunkZ the chunk Z
   * @return the chunk's root tag, or {@code null} if no chunk is saved at those coordinates
   * @throws IOException if the region file exists but this chunk cannot be read out of it
   */
  static @Nullable Map<String, Object> readChunk(Path regionFolder, int chunkX, int chunkZ)
      throws IOException {
    Path regionFile = regionFolder.resolve("r." + (chunkX >> 5) + "." + (chunkZ >> 5) + ".mca");
    if (!Files.isRegularFile(regionFile)) {
      return null; // nothing in this region has ever been saved
    }

    try (FileChannel channel = FileChannel.open(regionFile, StandardOpenOption.READ)) {
      // The header entry packs a three-byte sector offset and a one-byte sector count.
      int index = ((chunkX & 31) + (chunkZ & 31) * 32) * HEADER_ENTRY_BYTES;
      ByteBuffer entry = ByteBuffer.allocate(HEADER_ENTRY_BYTES);
      if (readFully(channel, entry, index) < HEADER_ENTRY_BYTES) {
        return null; // truncated header; treat as nothing saved
      }
      entry.flip();
      int location = entry.getInt();
      int offsetSectors = location >>> 8;
      int sectorCount = location & 0xFF;
      if (offsetSectors == 0 || sectorCount == 0) {
        return null; // this chunk has never been saved
      }

      ByteBuffer header = ByteBuffer.allocate(5);
      if (readFully(channel, header, (long) offsetSectors * SECTOR_BYTES) < 5) {
        throw new IOException("Chunk payload header is truncated");
      }
      header.flip();
      int length = header.getInt();
      int compression = header.get() & 0xFF;
      if (length <= 0 || length - 1 > MAX_PAYLOAD_BYTES) {
        throw new IOException("Chunk payload claims an implausible length: " + length);
      }

      if ((compression & EXTERNAL_FLAG) != 0) {
        Path external = regionFolder.resolve("c." + chunkX + "." + chunkZ + ".mcc");
        if (!Files.isRegularFile(external)) {
          throw new IOException("Chunk is marked oversized but " + external + " is missing");
        }
        try (InputStream in = new BufferedInputStream(Files.newInputStream(external))) {
          return parse(decompress(in, compression & ~EXTERNAL_FLAG));
        }
      }

      ByteBuffer payload = ByteBuffer.allocate(length - 1);
      long payloadAt = (long) offsetSectors * SECTOR_BYTES + 5;
      if (readFully(channel, payload, payloadAt) < payload.capacity()) {
        throw new IOException("Chunk payload is truncated");
      }
      return parse(decompress(new ByteArrayInputStream(payload.array()), compression));
    }
  }

  /**
   * Wraps a payload in whatever decompressor its id calls for.
   *
   * <p>Only the three compressions a server writes by default are handled. Minecraft 1.20.5 added
   * LZ4 and a custom id behind the {@code region-file-compression} setting, and a server set to one
   * of those is told so rather than guessed at — {@link AnvilOfflineChunkSource} answers by loading
   * the chunk properly, which is correct if slower.
   */
  private static InputStream decompress(InputStream in, int compression) throws IOException {
    return switch (compression) {
      case COMPRESSION_GZIP -> new GZIPInputStream(in);
      case COMPRESSION_ZLIB -> new InflaterInputStream(in);
      case COMPRESSION_NONE -> in;
      default ->
          throw new IOException(
              "Region file uses compression " + compression + ", which Cobblestone cannot read");
    };
  }

  private static Map<String, Object> parse(InputStream in) throws IOException {
    try (DataInputStream data = new DataInputStream(new BufferedInputStream(in))) {
      return Nbt.read(data);
    }
  }

  /** Reads until the buffer is full or the file ends, returning how many bytes were read. */
  private static int readFully(FileChannel channel, ByteBuffer buffer, long position)
      throws IOException {
    int total = 0;
    while (buffer.hasRemaining()) {
      int read = channel.read(buffer, position + total);
      if (read < 0) {
        break;
      }
      total += read;
    }
    return total;
  }
}
