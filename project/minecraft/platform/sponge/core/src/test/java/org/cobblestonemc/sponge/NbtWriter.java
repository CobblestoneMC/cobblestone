/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Writes NBT, so the tests can feed {@link Nbt} bytes in the shape Minecraft actually produces.
 *
 * <p>Written to the format specification rather than derived from the reader, which is the point: a
 * writer that mirrored the reader's mistakes would agree with it about everything and prove
 * nothing.
 */
final class NbtWriter {

  private NbtWriter() {}

  /** Serializes a compound as a root tag, exactly as a chunk is stored. */
  static byte[] write(Map<String, Object> root) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(bytes);
    out.writeByte(10);
    out.writeUTF("");
    writeCompoundBody(out, root);
    out.flush();
    return bytes.toByteArray();
  }

  private static void writeCompoundBody(DataOutputStream out, Map<String, Object> compound)
      throws IOException {
    for (Map.Entry<String, Object> entry : compound.entrySet()) {
      Object value = entry.getValue();
      out.writeByte(typeOf(value));
      out.writeUTF(entry.getKey());
      writePayload(out, value);
    }
    out.writeByte(0);
  }

  @SuppressWarnings("unchecked")
  private static void writePayload(DataOutputStream out, Object value) throws IOException {
    switch (value) {
      case Byte v -> out.writeByte(v);
      case Short v -> out.writeShort(v);
      case Integer v -> out.writeInt(v);
      case Long v -> out.writeLong(v);
      case Float v -> out.writeFloat(v);
      case Double v -> out.writeDouble(v);
      case String v -> out.writeUTF(v);
      case byte[] v -> {
        out.writeInt(v.length);
        out.write(v);
      }
      case int[] v -> {
        out.writeInt(v.length);
        for (int element : v) {
          out.writeInt(element);
        }
      }
      case long[] v -> {
        out.writeInt(v.length);
        for (long element : v) {
          out.writeLong(element);
        }
      }
      case List<?> v -> {
        out.writeByte(v.isEmpty() ? 0 : typeOf(v.get(0)));
        out.writeInt(v.size());
        for (Object element : v) {
          writePayload(out, element);
        }
      }
      case Map<?, ?> v -> writeCompoundBody(out, (Map<String, Object>) v);
      default -> throw new IllegalArgumentException("Cannot write " + value.getClass());
    }
  }

  private static int typeOf(Object value) {
    return switch (value) {
      case Byte ignored -> 1;
      case Short ignored -> 2;
      case Integer ignored -> 3;
      case Long ignored -> 4;
      case Float ignored -> 5;
      case Double ignored -> 6;
      case byte[] ignored -> 7;
      case String ignored -> 8;
      case List<?> ignored -> 9;
      case Map<?, ?> ignored -> 10;
      case int[] ignored -> 11;
      case long[] ignored -> 12;
      default -> throw new IllegalArgumentException("Cannot write " + value.getClass());
    };
  }
}
