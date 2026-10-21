/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper.plugin.api;

import java.util.Map;
import java.util.function.Supplier;
import org.bukkit.World;
import org.cobblestonemc.plugin.api.DestinationChild;
import org.cobblestonemc.plugin.api.DestinationLeaf;
import org.cobblestonemc.plugin.api.DestinationNode;
import org.cobblestonemc.plugin.api.MinecraftDestination;
import org.cobblestonemc.plugin.api.PlatformDestinationTree;
import org.cobblestonemc.plugin.api.Validity;
import org.joml.Vector3i;

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
 *         .children(() -> townDetail(riverwood)));
 * }</pre>
 *
 * <p>Each entry is a destination, a level of further entries, or both; {@link #destination} and
 * {@link #children} under the same key make one entry with both roles. Levels are held behind
 * {@link Supplier}s, so huge sets are not materialized until a level is actually visited, and a
 * destination is built only once a player picks it. A level is never itself somewhere to go — only
 * its entries are.
 */
public final class DestinationTree {

  private final DestinationNode.Builder<MinecraftDestination<World, Vector3i>> level =
      DestinationNode.builder();

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
   * it. Add it with {@link #child(String, DestinationChild.Builder)}.
   *
   * @return a new, empty entry builder
   */
  public static DestinationChild.Builder<MinecraftDestination<World, Vector3i>> child() {
    return DestinationChild.builder();
  }

  /**
   * Marks this level strict — its key may never be omitted in commands (no name-promotion).
   *
   * @return this builder
   */
  public DestinationTree strict() {
    level.strict();
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
      String key, DestinationChild<MinecraftDestination<World, Vector3i>> child) {
    level.child(key, child);
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
      String key, DestinationChild.Builder<MinecraftDestination<World, Vector3i>> child) {
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
      String key, DestinationLeaf<MinecraftDestination<World, Vector3i>> destination) {
    level.destination(key, destination);
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
      String key, Supplier<MinecraftDestination<World, Vector3i>> destination) {
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
      String key, MinecraftDestination<World, Vector3i> destination) {
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
      String key, Supplier<? extends PlatformDestinationTree<World, Vector3i>> tree) {
    level.children(key, tree);
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
   * Gives the destination under {@code key} another name, for players who guess it. An alias is
   * only ever typed: it is not suggested, and the destination is still addressed by its own key.
   *
   * @param alias the other name
   * @param key the key of a destination already added
   * @return this builder
   * @throws IllegalArgumentException if no destination has that key, or the alias is already a key
   *     here
   */
  public DestinationTree alias(String alias, String key) {
    level.alias(alias, key);
    return this;
  }

  /**
   * Builds the immutable level.
   *
   * @return the level
   */
  public PlatformDestinationTree<World, Vector3i> build() {
    DestinationNode<MinecraftDestination<World, Vector3i>> built = level.build();
    return new Node(built.strict(), built.children(), built.aliases());
  }

  private record Node(
      boolean strict,
      Map<String, DestinationChild<MinecraftDestination<World, Vector3i>>> children,
      Map<String, String> aliases)
      implements PlatformDestinationTree<World, Vector3i> {}
}
