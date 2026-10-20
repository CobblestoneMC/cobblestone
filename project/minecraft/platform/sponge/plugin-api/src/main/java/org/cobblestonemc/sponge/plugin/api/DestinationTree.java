/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge.plugin.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.cobblestonemc.plugin.api.DestinationChild;
import org.cobblestonemc.plugin.api.DestinationLeaf;
import org.cobblestonemc.plugin.api.Display;
import org.cobblestonemc.plugin.api.MinecraftDestination;
import org.cobblestonemc.plugin.api.PlatformDestinationTree;
import org.cobblestonemc.plugin.api.Validity;
import org.spongepowered.api.world.server.ServerWorld;
import org.spongepowered.math.vector.Vector3i;

/**
 * A fluent builder for one level of a {@link PlatformDestinationTree} — so an integration writes
 * its {@code /navigate} structure as structure, not nested {@code Map<String, Supplier<…>>}
 * plumbing:
 *
 * <pre>{@code
 * DestinationTree.builder()
 *     .children("town", towns)                          // a level of further entries
 *     .destination("spawn", () -> Destination.at(spawn, "spawn"))
 *     .child("riverwood", DestinationTree.child()       // somewhere to go, with more inside
 *         .destination(() -> wholeTown(riverwood))
 *         .children(() -> townDetail(riverwood))
 *         .label(Component.text("Riverwood"))
 *         .icon(SpongeIcon.of(ItemTypes.RED_BANNER.get())));
 * }</pre>
 *
 * <p>Each entry is a destination, a level of further entries, or both; {@link #destination} and
 * {@link #children} under the same key make one entry with both roles. Levels are held behind
 * {@link Supplier}s, so huge sets (every town, every home) are not materialized until a level is
 * actually visited, and a destination is built only once a player picks it. A level is never itself
 * somewhere to go — only its entries are.
 */
public final class DestinationTree {

  private boolean strict;
  private final Map<String, DestinationChild<MinecraftDestination<ServerWorld, Vector3i>>>
      children = new LinkedHashMap<>();

  private DestinationTree() {}

  /**
   * Begins a level. Its key is chosen by whoever attaches it — see {@link #children}.
   *
   * @return a new, empty level builder
   */
  public static DestinationTree builder() {
    return new DestinationTree();
  }

  /**
   * Begins one entry, for when it needs more than {@link #destination} or {@link #children} give
   * it: both roles at once, or a {@linkplain DestinationChild.Builder#label label}, {@linkplain
   * DestinationChild.Builder#description description} or {@linkplain DestinationChild.Builder#icon
   * icon} in the navigation menu. Add it with {@link #child(String, DestinationChild.Builder)}.
   *
   * @return a new, empty entry builder
   */
  public static DestinationChild.Builder<MinecraftDestination<ServerWorld, Vector3i>> child() {
    return DestinationChild.builder();
  }

  /**
   * Marks this level strict — its key may never be omitted in commands (no name-promotion).
   *
   * @return this builder
   */
  public DestinationTree strict() {
    this.strict = true;
    return this;
  }

  /**
   * Adds an entry, replacing any already under its key.
   *
   * @param key the entry's key
   * @param child the entry
   * @return this builder
   */
  public DestinationTree child(
      String key, DestinationChild<MinecraftDestination<ServerWorld, Vector3i>> child) {
    children.put(key, child);
    return this;
  }

  /**
   * Adds an entry, replacing any already under its key.
   *
   * @param key the entry's key
   * @param child the entry's builder, built now
   * @return this builder
   * @throws IllegalStateException if the entry was given neither a destination nor children
   */
  public DestinationTree child(
      String key, DestinationChild.Builder<MinecraftDestination<ServerWorld, Vector3i>> child) {
    return child(key, child.build());
  }

  /**
   * Makes the entry under {@code key} a destination, described by {@code destination} and built
   * once a player picks it — for one with permissions or options, or one that may be {@linkplain
   * Validity#invalid invalid}.
   *
   * @param key the entry's key
   * @param destination the destination
   * @return this builder
   */
  public DestinationTree destination(
      String key, DestinationLeaf<MinecraftDestination<ServerWorld, Vector3i>> destination) {
    children.merge(
        key,
        DestinationChild.ofDestination(destination),
        (existing, added) -> existing.withDestination(destination));
    return this;
  }

  /**
   * Makes the entry under {@code key} a destination, built once a player picks it.
   *
   * @param key the entry's key
   * @param destination supplies the destination when it is picked
   * @return this builder
   */
  public DestinationTree destination(
      String key, Supplier<MinecraftDestination<ServerWorld, Vector3i>> destination) {
    return destination(key, DestinationLeaf.of(destination));
  }

  /**
   * Makes the entry under {@code key} a destination.
   *
   * @param key the entry's key
   * @param destination the destination
   * @return this builder
   */
  public DestinationTree destination(
      String key, MinecraftDestination<ServerWorld, Vector3i> destination) {
    return destination(key, () -> destination);
  }

  /**
   * Gives the entry under {@code key} a level of further entries, built when it is visited.
   *
   * @param key the entry's key
   * @param tree supplies the level when it is visited
   * @return this builder
   */
  public DestinationTree children(
      String key, Supplier<? extends PlatformDestinationTree<ServerWorld, Vector3i>> tree) {
    children.merge(
        key, DestinationChild.ofChildren(tree), (existing, added) -> existing.withChildren(tree));
    return this;
  }

  /**
   * Gives the entry under {@code key} a level of further entries from another builder.
   *
   * @param key the entry's key
   * @param tree the builder of the level, built when it is visited
   * @return this builder
   */
  public DestinationTree children(String key, DestinationTree tree) {
    return children(key, tree::build);
  }

  /**
   * Sets how the navigation menu shows the entry already added under {@code key}.
   *
   * @param key the entry's key
   * @param display the display
   * @return this builder
   * @throws IllegalArgumentException if no entry has that key
   */
  public DestinationTree display(String key, Display display) {
    DestinationChild<MinecraftDestination<ServerWorld, Vector3i>> child = children.get(key);
    if (child == null) {
      throw new IllegalArgumentException("No entry " + key + " to display");
    }
    children.put(key, child.withDisplay(display));
    return this;
  }

  /**
   * Builds the immutable level.
   *
   * @return the level
   */
  public PlatformDestinationTree<ServerWorld, Vector3i> build() {
    return new Node(strict, Collections.unmodifiableMap(new LinkedHashMap<>(children)));
  }

  private record Node(
      boolean strict,
      Map<String, DestinationChild<MinecraftDestination<ServerWorld, Vector3i>>> children)
      implements PlatformDestinationTree<ServerWorld, Vector3i> {}
}
