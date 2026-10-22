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
import java.util.Objects;
import java.util.function.Supplier;

/**
 * A tree of destinations that {@code /navigate} walks: a set of named {@linkplain DestinationEntry
 * entries}, each a destination, a subtree of further entries, or both.
 *
 * <p>A tree has no destination of its own — only its entries do — so the tree a plugin hands
 * Cobblestone, which Cobblestone files under the plugin's name, can never itself be somewhere to
 * go.
 *
 * @param <D> what the destinations build
 */
public interface DestinationTree<D> {

  /**
   * Returns whether this tree is strict: its key may never be omitted in commands (no
   * name-promotion past it).
   *
   * @return {@code true} if strict
   */
  boolean strict();

  /**
   * Returns the entries of this tree, keyed by their key. A key is a single token typed in commands
   * and used in permission nodes: upper case is allowed, special characters are not, and spaces are
   * allowed but discouraged.
   *
   * @return the entries
   */
  Map<String, DestinationEntry<D>> entries();

  /**
   * Returns the other names the destinations in this tree answer to, each mapped to the key of the
   * entry it names. An alias is only ever typed: it is not suggested, and the destination it
   * reaches is addressed (in messages and permissions) by its own key.
   *
   * @return the aliases
   */
  default Map<String, String> aliases() {
    return Map.of();
  }

  /**
   * Begins a tree.
   *
   * @param <D> what the destinations build
   * @return a new, empty builder
   */
  static <D> Builder<D> builder() {
    return new Builder<>();
  }

  /**
   * A plain, immutable tree.
   *
   * @param strict whether its key may never be omitted
   * @param entries the entries
   * @param aliases the destinations' other names, each mapped to its entry's key
   * @param <D> what the destinations build
   */
  record Simple<D>(
      boolean strict, Map<String, DestinationEntry<D>> entries, Map<String, String> aliases)
      implements DestinationTree<D> {
    /** Canonical constructor; copies the maps, keeping their order. */
    public Simple {
      entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
      aliases = Map.copyOf(aliases);
    }

    /**
     * A tree without aliases.
     *
     * @param strict whether its key may never be omitted
     * @param entries the entries
     */
    public Simple(boolean strict, Map<String, DestinationEntry<D>> entries) {
      this(strict, entries, Map.of());
    }
  }

  /**
   * Builds a {@link DestinationTree}, keeping entries in the order they are added.
   *
   * <p>Each key is added once. For an entry that is both a destination and a subtree, build it with
   * {@link DestinationEntry#builder()} and add it with {@link #entry(String,
   * DestinationEntry.Builder)}.
   *
   * @param <D> what the destinations build
   */
  final class Builder<D> {

    private boolean strict;
    private final Map<String, DestinationEntry<D>> entries = new LinkedHashMap<>();
    private final Map<String, String> aliases = new LinkedHashMap<>();

    private Builder() {}

    /**
     * Marks the tree strict.
     *
     * @return this builder
     */
    public Builder<D> strict() {
      this.strict = true;
      return this;
    }

    /**
     * Adds an entry.
     *
     * @param key its key
     * @param entry the entry
     * @return this builder
     * @throws IllegalArgumentException if the key is already a key or an alias here
     */
    public Builder<D> entry(String key, DestinationEntry<D> entry) {
      Objects.requireNonNull(entry, "entry");
      if (entries.containsKey(key) || aliases.containsKey(key)) {
        throw new IllegalArgumentException(key + " is already a key or an alias here");
      }
      entries.put(key, entry);
      return this;
    }

    /**
     * Adds an entry.
     *
     * @param key its key
     * @param entry the entry's builder, built now
     * @return this builder
     * @throws IllegalArgumentException if the key is already a key or an alias here
     * @throws IllegalStateException if the entry was given neither a destination nor a subtree
     */
    public Builder<D> entry(String key, DestinationEntry.Builder<D> entry) {
      return entry(key, entry.build());
    }

    /**
     * Adds an entry that is a destination, described by {@code destination} — for one with
     * permissions or options, or one that may be {@linkplain Validity#invalid invalid}.
     *
     * @param key its key
     * @param destination the destination
     * @return this builder
     * @throws IllegalArgumentException if the key is already a key or an alias here
     */
    public Builder<D> destination(String key, DestinationLeaf<D> destination) {
      return entry(key, DestinationEntry.<D>builder().destination(destination));
    }

    /**
     * Adds an entry that is a destination, built once a player picks it.
     *
     * @param key its key
     * @param destination supplies the destination when it is picked
     * @return this builder
     * @throws IllegalArgumentException if the key is already a key or an alias here
     */
    public Builder<D> destination(String key, Supplier<? extends D> destination) {
      return destination(key, DestinationLeaf.of(destination));
    }

    /**
     * Adds an entry that holds a subtree, built each time it is visited.
     *
     * @param key its key
     * @param subtree supplies the subtree when it is visited
     * @return this builder
     * @throws IllegalArgumentException if the key is already a key or an alias here
     */
    public Builder<D> subtree(String key, Supplier<? extends DestinationTree<D>> subtree) {
      return entry(key, DestinationEntry.<D>builder().subtree(subtree));
    }

    /**
     * Adds an entry that holds a subtree from another builder, built now, once.
     *
     * @param key its key
     * @param subtree the subtree's builder
     * @return this builder
     * @throws IllegalArgumentException if the key is already a key or an alias here
     */
    public Builder<D> subtree(String key, Builder<D> subtree) {
      return entry(key, DestinationEntry.<D>builder().subtree(subtree));
    }

    /**
     * Gives the destination just added another name, for players who guess it; see {@link
     * #aliases()}.
     *
     * @param alias the other name
     * @param key the key of a destination already added
     * @return this builder
     * @throws IllegalArgumentException if no destination has that key, or the alias is already a
     *     key or an alias here
     */
    public Builder<D> alias(String alias, String key) {
      DestinationEntry<D> entry = entries.get(key);
      if (entry == null || entry.destination().isEmpty()) {
        throw new IllegalArgumentException("No destination " + key + " to alias");
      }
      if (entries.containsKey(alias) || aliases.containsKey(alias)) {
        throw new IllegalArgumentException(alias + " is already a key or an alias here");
      }
      aliases.put(alias, key);
      return this;
    }

    /**
     * Builds the tree.
     *
     * @return the tree
     */
    public DestinationTree<D> build() {
      return new Simple<>(strict, entries, aliases);
    }
  }
}
