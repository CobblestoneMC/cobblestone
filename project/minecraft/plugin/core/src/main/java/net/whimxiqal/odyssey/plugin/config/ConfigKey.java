/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.plugin.config;

/**
 * A typed handle to a single configuration parameter: its period-delimited, snake_case path (e.g.
 * {@code navigators.trail.particle_type}), default value, decoding {@link Codec}, and whether it
 * can change live on {@link ConfigManager#reload()}.
 *
 * <p>Obtain one from {@link ConfigManager#register}; read it with {@link ConfigManager#get}.
 *
 * @param <V> the value type
 */
public final class ConfigKey<V> {

  private final String path;
  private final V defaultValue;
  private final Codec<V> codec;
  private final boolean mutable;

  ConfigKey(String path, V defaultValue, Codec<V> codec, boolean mutable) {
    this.path = path;
    this.defaultValue = defaultValue;
    this.codec = codec;
    this.mutable = mutable;
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
   * Returns the value used when the key is absent or malformed.
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
}
