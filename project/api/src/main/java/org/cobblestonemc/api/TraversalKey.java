/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.api;

import java.util.Objects;

/**
 * A typed key into a {@link TraversalState}, enabling cast-free {@code get}/{@code with}.
 *
 * <p>Keys use <b>identity</b> equality and are expected to be created once as static singletons
 * (e.g. {@code MinecraftKeys.VEHICLE}); the {@code name} is only for diagnostics. Because equal
 * {@link TraversalState}s are built from the same key instances, identity equality keeps state
 * comparison correct and cheap.
 *
 * @param <V> the type of value stored under this key
 */
public final class TraversalKey<V> {

  private final String name;

  /**
   * Creates a key with the given diagnostic name.
   *
   * @param name a human-readable name used only for {@code toString}
   */
  public TraversalKey(String name) {
    this.name = Objects.requireNonNull(name, "name");
  }

  /**
   * Returns this key's diagnostic name.
   *
   * @return the name
   */
  public String name() {
    return name;
  }

  @Override
  public String toString() {
    return "TraversalKey[" + name + "]";
  }
}
