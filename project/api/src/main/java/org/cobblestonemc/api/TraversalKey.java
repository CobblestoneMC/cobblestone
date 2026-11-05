/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.api;

import java.util.Comparator;
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
  private final Comparator<V> preference;

  /**
   * Creates a key with the given diagnostic name. Different values of this key are simply different
   * conditions, and neither is better than the other.
   *
   * @param name a human-readable name used only for {@code toString}
   */
  public TraversalKey(String name) {
    this(name, null);
  }

  private TraversalKey(String name, Comparator<V> preference) {
    this.name = Objects.requireNonNull(name, "name");
    this.preference = preference;
  }

  /**
   * Creates a key whose values are ranked: a value that sorts earlier is never worse for the agent,
   * and having no value at all is best of all. Everything an agent can do with a later value it can
   * do with an earlier one, for no more cost, which is what lets the search drop a state that is
   * {@linkplain TraversalState#dominates dominated}. A key that does not keep that promise must not
   * be ranked, or the search will lose routes.
   *
   * @param name a human-readable name used only for {@code toString}
   * @param preference orders the values from best to worst
   * @param <V> the type of value stored under this key
   * @return the key
   */
  public static <V> TraversalKey<V> ranked(String name, Comparator<V> preference) {
    return new TraversalKey<>(name, Objects.requireNonNull(preference, "preference"));
  }

  /**
   * Returns how this key orders its values from best to worst.
   *
   * @return the order, or {@code null} if its values are not ranked
   */
  Comparator<V> preference() {
    return preference;
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
