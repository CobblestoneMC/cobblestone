/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.plugin.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Converts a raw YAML node (the loosely-typed {@code String}/{@code Number}/{@code Boolean}/
 * {@code List}/{@code Map} that a YAML parser yields) into a typed config value.
 *
 * <p>A codec throws {@link IllegalArgumentException} when the raw value cannot be interpreted; the
 * {@link ConfigManager} catches that, logs it, and falls back to the key's default.
 *
 * @param <V> the decoded value type
 */
@FunctionalInterface
public interface Codec<V> {

  /**
   * Decodes a raw YAML node.
   *
   * @param raw the raw value (never {@code null})
   * @return the typed value
   * @throws IllegalArgumentException if the raw value is not of the expected shape
   */
  V decode(Object raw);

  /**
   * Returns a codec for a plain string.
   *
   * @return the codec
   */
  static Codec<String> ofString() {
    return String::valueOf;
  }

  /**
   * Returns a codec for a boolean.
   *
   * @return the codec
   */
  static Codec<Boolean> ofBoolean() {
    return raw -> {
      if (raw instanceof Boolean bool) {
        return bool;
      }
      String text = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
      if (text.equals("true")) {
        return Boolean.TRUE;
      }
      if (text.equals("false")) {
        return Boolean.FALSE;
      }
      throw new IllegalArgumentException("expected a boolean but got '" + raw + "'");
    };
  }

  /**
   * Returns a codec for an integer.
   *
   * @return the codec
   */
  static Codec<Integer> ofInt() {
    return raw -> {
      if (raw instanceof Number number) {
        return number.intValue();
      }
      try {
        return Integer.parseInt(String.valueOf(raw).trim());
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("expected an integer but got '" + raw + "'");
      }
    };
  }

  /**
   * Returns a codec for a long.
   *
   * @return the codec
   */
  static Codec<Long> ofLong() {
    return raw -> {
      if (raw instanceof Number number) {
        return number.longValue();
      }
      try {
        return Long.parseLong(String.valueOf(raw).trim());
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("expected a long but got '" + raw + "'");
      }
    };
  }

  /**
   * Returns a codec for a double.
   *
   * @return the codec
   */
  static Codec<Double> ofDouble() {
    return raw -> {
      if (raw instanceof Number number) {
        return number.doubleValue();
      }
      try {
        return Double.parseDouble(String.valueOf(raw).trim());
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("expected a number but got '" + raw + "'");
      }
    };
  }

  /**
   * Returns a codec for an enum constant, matched case-insensitively by name.
   *
   * @param type the enum type
   * @param <E> the enum type
   * @return the codec
   */
  static <E extends Enum<E>> Codec<E> ofEnum(Class<E> type) {
    return raw -> {
      String name = String.valueOf(raw).trim().toUpperCase(Locale.ROOT);
      for (E constant : type.getEnumConstants()) {
        if (constant.name().equals(name)) {
          return constant;
        }
      }
      throw new IllegalArgumentException(
          "expected one of " + java.util.Arrays.toString(type.getEnumConstants()) + " but got '" + raw + "'");
    };
  }

  /**
   * Returns a codec for a list of strings.
   *
   * @return the codec
   */
  static Codec<List<String>> ofStringList() {
    return raw -> {
      if (!(raw instanceof List<?> list)) {
        throw new IllegalArgumentException("expected a list but got '" + raw + "'");
      }
      List<String> result = new ArrayList<>(list.size());
      for (Object element : list) {
        result.add(String.valueOf(element));
      }
      return List.copyOf(result);
    };
  }
}
