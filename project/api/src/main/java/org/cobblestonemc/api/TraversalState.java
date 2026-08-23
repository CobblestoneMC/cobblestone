/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.api;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * An immutable, sparse, hashable typed key→value map of accumulated agent condition during a search
 * (e.g. {@code VEHICLE → HORSE}).
 *
 * <p>{@link #DEFAULT} is the empty map — the common case, where the agent is in its base state. The
 * map only ever holds the overrides that differ from the base agent, so it stays tiny. Instances
 * are immutable; mutators return new states. This type never appears on the result {@link Step}; it
 * is purely internal to the search (the A* visited-set keys on {@code (cell, TraversalState)}).
 */
public final class TraversalState {

  /** The empty state — no overrides applied. */
  public static final TraversalState DEFAULT = new TraversalState(Map.of());

  private final Map<TraversalKey<?>, Object> values;

  private TraversalState(Map<TraversalKey<?>, Object> values) {
    this.values = values;
  }

  /**
   * Returns the value stored under {@code key}, or {@code null} if absent.
   *
   * @param key the typed key
   * @param <V> the value type
   * @return the stored value, or {@code null}
   */
  @SuppressWarnings("unchecked")
  public <V> V get(TraversalKey<V> key) {
    return (V) values.get(key);
  }

  /**
   * Returns whether a value is present for {@code key}.
   *
   * @param key the typed key
   * @return {@code true} if a value is present
   */
  public boolean contains(TraversalKey<?> key) {
    return values.containsKey(key);
  }

  /**
   * Returns a new state with {@code key} mapped to {@code value} (replacing any existing mapping).
   *
   * @param key the typed key
   * @param value the value to store (non-null)
   * @param <V> the value type
   * @return a new state with the mapping applied
   */
  public <V> TraversalState with(TraversalKey<V> key, V value) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(value, "value");
    Map<TraversalKey<?>, Object> copy = new HashMap<>(values);
    copy.put(key, value);
    return new TraversalState(Map.copyOf(copy));
  }

  /**
   * Returns a state with {@code key} removed, or this state if the key was absent.
   *
   * @param key the typed key to remove
   * @return a state without the given key
   */
  public TraversalState without(TraversalKey<?> key) {
    if (!values.containsKey(key)) {
      return this;
    }
    Map<TraversalKey<?>, Object> copy = new HashMap<>(values);
    copy.remove(key);
    return copy.isEmpty() ? DEFAULT : new TraversalState(Map.copyOf(copy));
  }

  /**
   * Returns whether this state has no overrides.
   *
   * @return {@code true} if this is the empty state
   */
  public boolean isEmpty() {
    return values.isEmpty();
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof TraversalState other && values.equals(other.values);
  }

  @Override
  public int hashCode() {
    return values.hashCode();
  }

  @Override
  public String toString() {
    return "TraversalState" + values;
  }
}
