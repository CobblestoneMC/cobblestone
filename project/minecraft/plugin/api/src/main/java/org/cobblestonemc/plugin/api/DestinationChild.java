/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.api;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * One named entry in a {@link DestinationNode}: a destination, a further level of entries, or both.
 *
 * <p>An entry with both roles is one place that also contains other places. Both {@code /navigate
 * <entry>} and {@code /navigate <entry> <inner>} then name it, the first going to the place itself.
 *
 * <p>An entry always has at least one of the two; {@link Builder#build} refuses one with neither.
 * The level beneath an entry is held behind a {@link Supplier}, so a large one is built only when
 * it is visited, and a destination is built only once a player picks it.
 *
 * @param <D> what the destinations build
 */
public final class DestinationChild<D> {

  private final DestinationLeaf<D> destination;
  private final Supplier<? extends DestinationNode<D>> children;

  private DestinationChild(
      DestinationLeaf<D> destination, Supplier<? extends DestinationNode<D>> children) {
    this.destination = destination;
    this.children = children;
  }

  /**
   * Begins an entry.
   *
   * @param <D> what the destinations build
   * @return a new, empty builder
   */
  public static <D> Builder<D> builder() {
    return new Builder<>();
  }

  /**
   * An entry that is only a destination.
   *
   * @param destination the destination
   * @param <D> what the destinations build
   * @return the entry
   */
  public static <D> DestinationChild<D> ofDestination(DestinationLeaf<D> destination) {
    return new DestinationChild<>(Objects.requireNonNull(destination, "destination"), null);
  }

  /**
   * An entry that only holds a further level.
   *
   * @param children builds the level when it is visited
   * @param <D> what the destinations build
   * @return the entry
   */
  public static <D> DestinationChild<D> ofChildren(
      Supplier<? extends DestinationNode<D>> children) {
    return new DestinationChild<>(null, Objects.requireNonNull(children, "children"));
  }

  /**
   * The destination this entry names, if it is one.
   *
   * @return the destination
   */
  public Optional<DestinationLeaf<D>> destination() {
    return Optional.ofNullable(destination);
  }

  /**
   * The level beneath this entry, if it has one — not built until the supplier is called.
   *
   * @return builds the level
   */
  public Optional<Supplier<? extends DestinationNode<D>>> children() {
    return Optional.ofNullable(children);
  }

  /**
   * Returns a copy that is (also) the given destination.
   *
   * @param destination the destination
   * @return the copy
   */
  public DestinationChild<D> withDestination(DestinationLeaf<D> destination) {
    return new DestinationChild<>(Objects.requireNonNull(destination, "destination"), children);
  }

  /**
   * Returns a copy that (also) holds the given level.
   *
   * @param children builds the level when it is visited
   * @return the copy
   */
  public DestinationChild<D> withChildren(Supplier<? extends DestinationNode<D>> children) {
    return new DestinationChild<>(destination, Objects.requireNonNull(children, "children"));
  }

  /**
   * Builds a {@link DestinationChild}.
   *
   * @param <D> what the destinations build
   */
  public static final class Builder<D> {

    private DestinationLeaf<D> destination;
    private Supplier<? extends DestinationNode<D>> children;

    private Builder() {}

    /**
     * Makes the entry a destination, described by {@code destination} — for one with permissions or
     * options, or one that may be {@linkplain Validity#invalid invalid}.
     *
     * @param destination the destination
     * @return this builder
     */
    public Builder<D> destination(DestinationLeaf<D> destination) {
      this.destination = Objects.requireNonNull(destination, "destination");
      return this;
    }

    /**
     * Makes the entry a destination, built once a player picks it.
     *
     * @param destination supplies the destination
     * @return this builder
     */
    public Builder<D> destination(Supplier<? extends D> destination) {
      return destination(DestinationLeaf.of(destination));
    }

    /**
     * Gives the entry a level beneath it, built when it is visited.
     *
     * @param children builds the level
     * @return this builder
     */
    public Builder<D> children(Supplier<? extends DestinationNode<D>> children) {
      this.children = Objects.requireNonNull(children, "children");
      return this;
    }

    /**
     * Builds the entry.
     *
     * @return the entry
     * @throws IllegalStateException if it was given neither a destination nor children
     */
    public DestinationChild<D> build() {
      if (destination == null && children == null) {
        throw new IllegalStateException(
            "A destination child needs a destination, children, or both");
      }
      return new DestinationChild<>(destination, children);
    }
  }
}
