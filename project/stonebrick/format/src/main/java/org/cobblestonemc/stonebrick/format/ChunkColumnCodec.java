/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.format;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Reads and writes {@code .sbc} column files.
 *
 * <p>The layout, in order:
 *
 * <pre>
 *   magic         u32   "SBC1"
 *   version       u8
 *   flags         u8    reserved, must be 0
 *   chunkX        i32
 *   chunkZ        i32
 *   minSectionY   i16
 *   sectionMask   i64
 *   paletteCount  u16
 *   palette       paletteCount × (u16 byteLength, UTF-8 bytes)
 *   per set bit of sectionMask, ascending:
 *     kind        u8
 *     uniform:    paletteId u16
 *     packed:     localCount u16, localIds u16[localCount], bitsPerEntry u8, data i64[…]
 *   crc32         u32   over every preceding byte
 * </pre>
 *
 * <p><b>Writing is a pure function of the column.</b> Palette order is first-seen, local index
 * tables are first-seen, and nothing is compressed — so re-capturing unchanged terrain produces
 * byte-identical output and no version-control diff. Anything that broke that property (a hash-set
 * iteration order, a compressor, a timestamp) would turn every re-capture into churn.
 *
 * <p>The trailing CRC is checked on read and a mismatch throws. A corrupt capture that decoded to
 * plausible-looking blocks would produce a benchmark number that nothing downstream could tell was
 * wrong.
 */
public final class ChunkColumnCodec {

  private static final int MAX_PALETTE = 0xFFFF;

  private ChunkColumnCodec() {}

  /**
   * Encodes a column.
   *
   * @param column the column
   * @return the file bytes
   * @throws CaptureFormatException if the column cannot be represented (too large a palette)
   */
  public static byte[] write(ChunkColumn column) throws CaptureFormatException {
    BlockPalette palette = column.palette();
    if (palette.size() > MAX_PALETTE) {
      throw new CaptureFormatException(
          "palette of " + palette.size() + " exceeds the " + MAX_PALETTE + " entry limit");
    }
    ByteArrayOutputStream bytes = new ByteArrayOutputStream(8192);
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      out.writeInt(Sbc.MAGIC);
      out.writeByte(Sbc.VERSION);
      out.writeByte(0); // flags
      out.writeInt(column.chunkX());
      out.writeInt(column.chunkZ());
      out.writeShort(column.minSectionY());
      out.writeLong(column.sectionMask());

      out.writeShort(palette.size());
      for (String state : palette.states()) {
        byte[] utf8 = state.getBytes(StandardCharsets.UTF_8);
        if (utf8.length > MAX_PALETTE) {
          throw new CaptureFormatException("block state string is too long: " + state);
        }
        out.writeShort(utf8.length);
        out.write(utf8);
      }

      long mask = column.sectionMask();
      for (int layer = 0; layer < Sbc.MAX_LAYERS; layer++) {
        if ((mask & (1L << layer)) == 0) {
          continue;
        }
        Cube cube = column.cubeAtLayer(layer);
        if (cube instanceof Cube.Uniform uniform) {
          out.writeByte(Sbc.KIND_UNIFORM);
          out.writeShort(uniform.index());
        } else if (cube instanceof Cube.Packed packed) {
          out.writeByte(Sbc.KIND_PACKED);
          int[] localIds = packed.localIds();
          out.writeShort(localIds.length);
          for (int id : localIds) {
            out.writeShort(id);
          }
          out.writeByte(packed.bitsPerEntry());
          for (long word : packed.data()) {
            out.writeLong(word);
          }
        } else {
          throw new CaptureFormatException(
              "section mask claims layer " + layer + " but no cube is present");
        }
      }
    } catch (IOException e) {
      // A ByteArrayOutputStream does not do IO; anything thrown here is a bug, not a disk.
      throw new CaptureFormatException("failed to encode column " + column, e);
    }

    byte[] body = bytes.toByteArray();
    CRC32 crc = new CRC32();
    crc.update(body);
    byte[] file = new byte[body.length + Integer.BYTES];
    System.arraycopy(body, 0, file, 0, body.length);
    int checksum = (int) crc.getValue();
    file[body.length] = (byte) (checksum >>> 24);
    file[body.length + 1] = (byte) (checksum >>> 16);
    file[body.length + 2] = (byte) (checksum >>> 8);
    file[body.length + 3] = (byte) checksum;
    return file;
  }

  /**
   * Decodes a column.
   *
   * @param file the file bytes
   * @return the column
   * @throws CaptureFormatException if the bytes are not a readable column
   */
  public static ChunkColumn read(byte[] file) throws CaptureFormatException {
    if (file.length < Integer.BYTES) {
      throw new CaptureFormatException(
          "file is " + file.length + " bytes; too short to be a column");
    }
    int bodyLength = file.length - Integer.BYTES;
    CRC32 crc = new CRC32();
    crc.update(file, 0, bodyLength);
    int expected =
        ((file[bodyLength] & 0xFF) << 24)
            | ((file[bodyLength + 1] & 0xFF) << 16)
            | ((file[bodyLength + 2] & 0xFF) << 8)
            | (file[bodyLength + 3] & 0xFF);
    if ((int) crc.getValue() != expected) {
      throw new CaptureFormatException(
          "checksum mismatch: the capture file is corrupt and would decode to wrong blocks");
    }

    try (DataInputStream in =
        new DataInputStream(new java.io.ByteArrayInputStream(file, 0, bodyLength))) {
      int magic = in.readInt();
      if (magic != Sbc.MAGIC) {
        throw new CaptureFormatException(
            "not an .sbc column: magic was 0x" + Integer.toHexString(magic));
      }
      int version = in.readUnsignedByte();
      if (version != Sbc.VERSION) {
        throw new CaptureFormatException(
            "capture format version " + version + "; this build reads version " + Sbc.VERSION);
      }
      int flags = in.readUnsignedByte();
      if (flags != 0) {
        throw new CaptureFormatException("unknown flags 0x" + Integer.toHexString(flags));
      }
      int chunkX = in.readInt();
      int chunkZ = in.readInt();
      int minSectionY = in.readShort();
      long sectionMask = in.readLong();

      int paletteCount = in.readUnsignedShort();
      List<String> states = new ArrayList<>(paletteCount);
      for (int i = 0; i < paletteCount; i++) {
        byte[] utf8 = new byte[in.readUnsignedShort()];
        in.readFully(utf8);
        states.add(new String(utf8, StandardCharsets.UTF_8));
      }
      BlockPalette palette = BlockPalette.of(states);

      Cube[] cubes = new Cube[Sbc.MAX_LAYERS];
      for (int layer = 0; layer < Sbc.MAX_LAYERS; layer++) {
        if ((sectionMask & (1L << layer)) == 0) {
          continue;
        }
        cubes[layer] = readCube(in, paletteCount, layer);
      }
      if (in.read() != -1) {
        throw new CaptureFormatException("trailing bytes after the last cube");
      }
      return new ChunkColumn(chunkX, chunkZ, minSectionY, sectionMask, palette, cubes);
    } catch (EOFException e) {
      throw new CaptureFormatException("column file ends mid-record", e);
    } catch (IOException e) {
      throw new CaptureFormatException("failed to decode column", e);
    }
  }

  private static Cube readCube(DataInputStream in, int paletteCount, int layer) throws IOException {
    int kind = in.readUnsignedByte();
    switch (kind) {
      case Sbc.KIND_UNIFORM -> {
        int index = in.readUnsignedShort();
        requirePaletteIndex(index, paletteCount, layer);
        return new Cube.Uniform(index);
      }
      case Sbc.KIND_PACKED -> {
        int localCount = in.readUnsignedShort();
        if (localCount < 2) {
          throw new CaptureFormatException(
              "layer " + layer + " is packed with " + localCount + " states; should be uniform");
        }
        int[] localIds = new int[localCount];
        for (int i = 0; i < localCount; i++) {
          localIds[i] = in.readUnsignedShort();
          requirePaletteIndex(localIds[i], paletteCount, layer);
        }
        int bits = in.readUnsignedByte();
        int expectedBits = Sbc.bitsPerEntry(localCount);
        if (bits != expectedBits) {
          throw new CaptureFormatException(
              "layer "
                  + layer
                  + " declares "
                  + bits
                  + " bits for "
                  + localCount
                  + " states; expected "
                  + expectedBits);
        }
        long[] data = new long[Sbc.packedLength(bits)];
        for (int i = 0; i < data.length; i++) {
          data[i] = in.readLong();
        }
        return new Cube.Packed(localIds, data, bits);
      }
      default ->
          throw new CaptureFormatException("layer " + layer + " has unknown cube kind " + kind);
    }
  }

  private static void requirePaletteIndex(int index, int paletteCount, int layer)
      throws CaptureFormatException {
    if (index >= paletteCount) {
      throw new CaptureFormatException(
          "layer " + layer + " references palette index " + index + " of " + paletteCount);
    }
  }

  /**
   * Reads a column from disk.
   *
   * @param path the file
   * @return the column
   * @throws IOException if the file cannot be read or decoded
   */
  public static ChunkColumn read(Path path) throws IOException {
    try {
      return read(Files.readAllBytes(path));
    } catch (CaptureFormatException e) {
      throw new CaptureFormatException(path + ": " + e.getMessage(), e);
    }
  }

  /**
   * Writes a column to disk, creating parent directories.
   *
   * <p>Rewrites the file only when its bytes differ, so a re-capture of unchanged terrain leaves
   * the modification time — and the working tree — alone.
   *
   * @param column the column
   * @param path the file
   * @return {@code true} if the file was written, {@code false} if it was already identical
   * @throws IOException if the file cannot be written
   */
  public static boolean write(ChunkColumn column, Path path) throws IOException {
    byte[] encoded = write(column);
    if (Files.exists(path) && java.util.Arrays.equals(Files.readAllBytes(path), encoded)) {
      return false;
    }
    Path parent = path.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Files.write(path, encoded);
    return true;
  }
}
