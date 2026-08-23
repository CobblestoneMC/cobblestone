/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.config;

import java.util.List;

/**
 * A typed handle to a single configuration parameter: its period-delimited, snake_case path (e.g.
 * {@code navigators.trail.particle_type}), default value, decoding {@link Codec}, whether it can
 * change live on {@link ConfigManager#reload()}, and the documentation emitted for it when the
 * config template is generated.
 *
 * <p>Obtain one from {@link ConfigManager#key}; read it with {@link ConfigManager#get}.
 *
 * @param <V> the value type
 */
public final class ConfigKey<V> {

  private final String path;
  private final V defaultValue;
  private final Codec<V> codec;
  private final boolean mutable;
  private final List<String> comment;
  private final List<V> permitted;

  ConfigKey(
      String path,
      V defaultValue,
      Codec<V> codec,
      boolean mutable,
      List<String> comment,
      List<V> permitted) {
    this.path = path;
    this.defaultValue = defaultValue;
    this.codec = codec;
    this.mutable = mutable;
    this.comment = comment;
    this.permitted = permitted;
  }

  /**
   * Returns the period-delimited path of this key.
   *
   * @return the path
   */
  public String path() {
    return path;
  }

  /**
   * Returns the final segment of this key's path — its name within its section.
   *
   * @return the leaf name
   */
  public String name() {
    int dot = path.lastIndexOf('.');
    return dot < 0 ? path : path.substring(dot + 1);
  }

  /**
   * Returns the value used when the key is absent, malformed, or not permitted.
   *
   * @return the default value
   */
  public V defaultValue() {
    return defaultValue;
  }

  /**
   * Returns the codec that decodes this key's raw YAML node.
   *
   * @return the codec
   */
  public Codec<V> codec() {
    return codec;
  }

  /**
   * Returns whether this key updates live on reload. Immutable keys that change in the file emit a
   * "requires restart" warning and keep their loaded value.
   *
   * @return {@code true} if mutable
   */
  public boolean mutable() {
    return mutable;
  }

  /**
   * Returns the documentation lines emitted above this key in the generated template, already
   * wrapped and without the leading {@code #}.
   *
   * @return the comment lines (possibly empty)
   */
  public List<String> comment() {
    return comment;
  }

  /**
   * Returns the values this platform accepts, or an empty list when unrestricted. A decoded value
   * outside a non-empty set is rejected with a warning and replaced by the fallback — this is what
   * makes a config copied from another platform announce itself rather than silently changing
   * meaning.
   *
   * @return the permitted values, or empty for unrestricted
   */
  public List<V> permitted() {
    return permitted;
  }
}
