/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge;

import java.io.DataInput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A reader for Minecraft's NBT format, just large enough to get the blocks out of a saved chunk.
 *
 * <p>Cobblestone carries its own rather than calling the server's because the server's lives in
 * {@code net.minecraft}, and reaching into that is the thing this whole path exists to avoid: those
 * classes are renamed and reshaped every Minecraft version, while NBT itself has been unchanged
 * since 2011 and the chunk layout built on it since 1.18. Two hundred lines that never need
 * touching beat a dependency that needs revisiting every release.
 *
 * <p>Tags are decoded into plain Java: a compound becomes a {@link Map}, a list a {@link List},
 * arrays their primitive counterparts, and numbers their boxed types. Callers read what they need
 * through the typed accessors below, which answer {@code null} rather than throwing when a tag is
 * absent or of an unexpected type — a saved chunk is a file on disk that may be damaged or written
 * by something that is not this server, so every field it offers is treated as a claim rather than
 * a guarantee.
 *
 * <p>Sizes are capped (see {@link #MAX_ELEMENTS}) so that a corrupt length field costs an exception
 * rather than the heap.
 */
final class Nbt {

  private static final byte TAG_END = 0;
  private static final byte TAG_BYTE = 1;
  private static final byte TAG_SHORT = 2;
  private static final byte TAG_INT = 3;
  private static final byte TAG_LONG = 4;
  private static final byte TAG_FLOAT = 5;
  private static final byte TAG_DOUBLE = 6;
  private static final byte TAG_BYTE_ARRAY = 7;
  private static final byte TAG_STRING = 8;
  private static final byte TAG_LIST = 9;
  private static final byte TAG_COMPOUND = 10;
  private static final byte TAG_INT_ARRAY = 11;
  private static final byte TAG_LONG_ARRAY = 12;

  /**
   * The most elements any one list or array may claim to hold.
   *
   * <p>A length is four bytes read straight off disk, so a damaged file can ask for two billion
   * entries before a single one is read. Nothing in a chunk comes close to this bound — the largest
   * real array is a section's packed block data, a few hundred longs — so it only ever fires on
   * data that was not going to decode anyway.
   */
  private static final int MAX_ELEMENTS = 1 << 22;

  /** How deeply tags may nest, so a compound that contains itself cannot exhaust the stack. */
  private static final int MAX_DEPTH = 64;

  private Nbt() {}

  /**
   * Reads a root compound tag.
   *
   * @param input the uncompressed NBT stream
   * @return the root compound
   * @throws IOException if the stream is not a well-formed NBT compound
   */
  static Map<String, Object> read(DataInput input) throws IOException {
    byte type = input.readByte();
    if (type != TAG_COMPOUND) {
      throw new IOException("Expected an NBT compound at the root, found tag type " + type);
    }
    input.skipBytes(input.readUnsignedShort()); // the root tag's name, which nothing uses
    return readCompound(input, 0);
  }

  private static Map<String, Object> readCompound(DataInput input, int depth) throws IOException {
    if (depth > MAX_DEPTH) {
      throw new IOException("NBT nested more than " + MAX_DEPTH + " deep");
    }
    Map<String, Object> compound = new HashMap<>();
    while (true) {
      byte type = input.readByte();
      if (type == TAG_END) {
        return compound;
      }
      String name = input.readUTF();
      compound.put(name, readPayload(input, type, depth + 1));
    }
  }

  private static Object readPayload(DataInput input, byte type, int depth) throws IOException {
    if (depth > MAX_DEPTH) {
      throw new IOException("NBT nested more than " + MAX_DEPTH + " deep");
    }
    switch (type) {
      case TAG_BYTE:
        return input.readByte();
      case TAG_SHORT:
        return input.readShort();
      case TAG_INT:
        return input.readInt();
      case TAG_LONG:
        return input.readLong();
      case TAG_FLOAT:
        return input.readFloat();
      case TAG_DOUBLE:
        return input.readDouble();
      case TAG_STRING:
        return input.readUTF();
      case TAG_BYTE_ARRAY:
        {
          byte[] values = new byte[length(input)];
          input.readFully(values);
          return values;
        }
      case TAG_INT_ARRAY:
        {
          int[] values = new int[length(input)];
          for (int i = 0; i < values.length; i++) {
            values[i] = input.readInt();
          }
          return values;
        }
      case TAG_LONG_ARRAY:
        {
          long[] values = new long[length(input)];
          for (int i = 0; i < values.length; i++) {
            values[i] = input.readLong();
          }
          return values;
        }
      case TAG_LIST:
        {
          byte elementType = input.readByte();
          int count = length(input);
          List<Object> values = new ArrayList<>(Math.min(count, 1024));
          for (int i = 0; i < count; i++) {
            // A list of TAG_END is how an empty list is written; it has no payloads to read.
            values.add(elementType == TAG_END ? null : readPayload(input, elementType, depth + 1));
          }
          return values;
        }
      case TAG_COMPOUND:
        return readCompound(input, depth + 1);
      default:
        throw new IOException("Unknown NBT tag type " + type);
    }
  }

  private static int length(DataInput input) throws IOException {
    int length = input.readInt();
    if (length < 0 || length > MAX_ELEMENTS) {
      throw new IOException("NBT array or list claims an implausible length: " + length);
    }
    return length;
  }

  /**
   * Returns a child compound, or {@code null} if absent or not a compound.
   *
   * @param compound the parent
   * @param name the child's name
   * @return the child compound, or {@code null}
   */
  @SuppressWarnings("unchecked")
  static Map<String, Object> compound(Map<String, Object> compound, String name) {
    Object value = compound.get(name);
    return value instanceof Map ? (Map<String, Object>) value : null;
  }

  /**
   * Returns a child list, or an empty list if absent or not a list.
   *
   * @param compound the parent
   * @param name the child's name
   * @return the child list, never {@code null}
   */
  @SuppressWarnings("unchecked")
  static List<Object> list(Map<String, Object> compound, String name) {
    Object value = compound.get(name);
    return value instanceof List ? (List<Object>) value : List.of();
  }

  /**
   * Returns a child string, or {@code null} if absent or not a string.
   *
   * @param compound the parent
   * @param name the child's name
   * @return the string, or {@code null}
   */
  static String string(Map<String, Object> compound, String name) {
    Object value = compound.get(name);
    return value instanceof String string ? string : null;
  }

  /**
   * Returns a child number, or a default if absent or not a number.
   *
   * @param compound the parent
   * @param name the child's name
   * @param fallback what to answer when the tag is missing or of another type
   * @return the number as an {@code int}
   */
  static int integer(Map<String, Object> compound, String name, int fallback) {
    Object value = compound.get(name);
    return value instanceof Number number ? number.intValue() : fallback;
  }

  /**
   * Returns a child long array, or {@code null} if absent or not a long array.
   *
   * @param compound the parent
   * @param name the child's name
   * @return the array, or {@code null}
   */
  static long[] longArray(Map<String, Object> compound, String name) {
    Object value = compound.get(name);
    return value instanceof long[] values ? values : null;
  }
}
