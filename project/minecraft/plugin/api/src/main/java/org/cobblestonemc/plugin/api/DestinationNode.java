/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * One level of the destination tree that {@code /navigate} and the navigation menu walk: a set of
 * named {@linkplain DestinationChild entries}, each a destination, a further level, or both.
 *
 * <p>A level has no destination of its own — only its entries do — so the level a plugin hands
 * Cobblestone, which Cobblestone files under the plugin's name, can never itself be somewhere to
 * go.
 *
 * @param <D> what the destinations build
 */
public interface DestinationNode<D> {

  /**
   * Returns whether this level is strict: its key may never be omitted in commands (no
   * name-promotion past it).
   *
   * @return {@code true} if strict
   */
  boolean strict();

  /**
   * Returns the entries at this level, keyed by their key.
   *
   * @return the entries
   */
  Map<String, DestinationChild<D>> children();

  /**
   * Returns the other names the destinations at this level answer to, each mapped to the key of the
   * entry it names. An alias is only ever typed: it is not suggested or shown in the menu, and the
   * destination it reaches is addressed (in messages and permissions) by its own key.
   *
   * @return the aliases
   */
  default Map<String, String> aliases() {
    return Map.of();
  }

  /**
   * Begins a level.
   *
   * @param <D> what the destinations build
   * @return a new, empty builder
   */
  static <D> Builder<D> builder() {
    return new Builder<>();
  }

  /**
   * A plain, immutable level.
   *
   * @param strict whether its key may never be omitted
   * @param children the entries
   * @param aliases the destinations' other names, each mapped to its entry's key
   * @param <D> what the destinations build
   */
  record Simple<D>(
      boolean strict, Map<String, DestinationChild<D>> children, Map<String, String> aliases)
      implements DestinationNode<D> {
    /** Canonical constructor; copies the maps, keeping their order. */
    public Simple {
      children = Collections.unmodifiableMap(new LinkedHashMap<>(children));
      aliases = Map.copyOf(aliases);
    }

    /**
     * A level without aliases.
     *
     * @param strict whether its key may never be omitted
     * @param children the entries
     */
    public Simple(boolean strict, Map<String, DestinationChild<D>> children) {
      this(strict, children, Map.of());
    }
  }

  /**
   * Builds a {@link DestinationNode}, keeping entries in the order they are added.
   *
   * <p>{@link #destination} and {@link #children} under the same key make one entry with both
   * roles, whichever comes first.
   *
   * @param <D> what the destinations build
   */
  final class Builder<D> {

    private boolean strict;
    private final Map<String, DestinationChild<D>> children = new LinkedHashMap<>();
    private final Map<String, String> aliases = new LinkedHashMap<>();

    private Builder() {}

    /**
     * Marks the level strict.
     *
     * @return this builder
     */
    public Builder<D> strict() {
      this.strict = true;
      return this;
    }

    /**
     * Adds an entry, replacing any already under its key.
     *
     * @param key its key
     * @param child the entry
     * @return this builder
     */
    public Builder<D> child(String key, DestinationChild<D> child) {
      children.put(key, child);
      return this;
    }

    /**
     * Makes the entry under {@code key} a destination, adding the entry if there is none yet.
     *
     * @param key its key
     * @param destination the destination
     * @return this builder
     */
    public Builder<D> destination(String key, DestinationLeaf<D> destination) {
      children.merge(
          key,
          DestinationChild.ofDestination(destination),
          (existing, added) -> existing.withDestination(destination));
      return this;
    }

    /**
     * Gives the entry under {@code key} a level beneath it, adding the entry if there is none yet.
     *
     * @param key its key
     * @param level builds the level when it is visited
     * @return this builder
     */
    public Builder<D> children(String key, Supplier<? extends DestinationNode<D>> level) {
      children.merge(
          key,
          DestinationChild.ofChildren(level),
          (existing, added) -> existing.withChildren(level));
      return this;
    }

    /**
     * Sets how the navigation menu shows the entry already added under {@code key}.
     *
     * @param key the entry's key
     * @param display the display
     * @return this builder
     * @throws IllegalArgumentException if no entry has that key
     */
    public Builder<D> display(String key, Display display) {
      DestinationChild<D> child = children.get(key);
      if (child == null) {
        throw new IllegalArgumentException("No entry " + key + " to display");
      }
      children.put(key, child.withDisplay(display));
      return this;
    }

    /**
     * Gives the destination just added another name, for players who guess it; see {@link
     * #aliases()}.
     *
     * @param alias the other name
     * @param key the key of a destination already added
     * @return this builder
     * @throws IllegalArgumentException if no destination has that key, or the alias is already a
     *     key here
     */
    public Builder<D> alias(String alias, String key) {
      DestinationChild<D> child = children.get(key);
      if (child == null || child.destination().isEmpty()) {
        throw new IllegalArgumentException("No destination " + key + " to alias");
      }
      if (children.containsKey(alias)) {
        throw new IllegalArgumentException(alias + " is already a key here");
      }
      aliases.put(alias, key);
      return this;
    }

    /**
     * Builds the level.
     *
     * @return the level
     */
    public DestinationNode<D> build() {
      return new Simple<>(strict, children, aliases);
    }
  }
}
