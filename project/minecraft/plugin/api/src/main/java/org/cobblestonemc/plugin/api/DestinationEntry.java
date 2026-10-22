/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;

/**
 * One named entry in a {@link DestinationTree}: a destination, a subtree of further entries, or
 * both.
 *
 * <p>An entry with both roles is one place that also contains other places. Both {@code /navigate
 * <entry>} and {@code /navigate <entry> <inner>} then name it, the first going to the place itself.
 *
 * <p>An entry always has at least one of the two; {@link Builder#build} refuses one with neither.
 * Its subtree is held behind a {@link Supplier}, so a large one is built only when it is visited,
 * and its destination is built only once a player picks it.
 *
 * @param <D> what the destinations build
 */
public final class DestinationEntry<D> {

  private final DestinationLeaf<D> destination;
  private final Supplier<? extends DestinationTree<D>> subtree;
  private final Display display;

  private DestinationEntry(
      DestinationLeaf<D> destination,
      Supplier<? extends DestinationTree<D>> subtree,
      Display display) {
    this.destination = destination;
    this.subtree = subtree;
    this.display = Objects.requireNonNull(display, "display");
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
   * The destination this entry names, if it is one.
   *
   * @return the destination
   */
  public Optional<DestinationLeaf<D>> destination() {
    return Optional.ofNullable(destination);
  }

  /**
   * The subtree beneath this entry, if it has one — not built until the supplier is called.
   *
   * @return builds the subtree
   */
  public Optional<Supplier<? extends DestinationTree<D>>> subtree() {
    return Optional.ofNullable(subtree);
  }

  /**
   * How the navigation menu shows this entry.
   *
   * @return the display
   */
  public Display display() {
    return display;
  }

  /**
   * Returns a copy shown with the given display.
   *
   * @param display the display
   * @return the copy
   */
  public DestinationEntry<D> withDisplay(Display display) {
    return new DestinationEntry<>(destination, subtree, display);
  }

  /**
   * Builds a {@link DestinationEntry}.
   *
   * @param <D> what the destinations build
   */
  public static final class Builder<D> {

    private DestinationLeaf<D> destination;
    private Supplier<? extends DestinationTree<D>> subtree;
    private Display display = Display.none();

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
     * Gives the entry a subtree, built when it is visited.
     *
     * @param subtree supplies the subtree
     * @return this builder
     */
    public Builder<D> subtree(Supplier<? extends DestinationTree<D>> subtree) {
      this.subtree = Objects.requireNonNull(subtree, "subtree");
      return this;
    }

    /**
     * Sets everything the menu shows for the entry at once.
     *
     * @param display the display
     * @return this builder
     */
    public Builder<D> display(Display display) {
      this.display = Objects.requireNonNull(display, "display");
      return this;
    }

    /**
     * Sets the name the menu shows, in place of the entry's key.
     *
     * @param label the name
     * @return this builder
     */
    public Builder<D> label(Component label) {
      this.display = display.withLabel(label);
      return this;
    }

    /**
     * Sets the lines the menu shows beneath the name.
     *
     * @param description the lines
     * @return this builder
     */
    public Builder<D> description(List<Component> description) {
      this.display = display.withDescription(description);
      return this;
    }

    /**
     * Sets the lines the menu shows beneath the name.
     *
     * @param description the lines
     * @return this builder
     */
    public Builder<D> description(Component... description) {
      return description(List.of(description));
    }

    /**
     * Sets the item the menu shows.
     *
     * @param icon the item, or {@code null} for the menu's default
     * @return this builder
     */
    public Builder<D> icon(Icon icon) {
      this.display = display.withIcon(icon);
      return this;
    }

    /**
     * Gives the entry a subtree from another builder, built now, once.
     *
     * @param subtree the subtree's builder
     * @return this builder
     */
    public Builder<D> subtree(DestinationTree.Builder<D> subtree) {
      DestinationTree<D> built = subtree.build();
      return subtree(() -> built);
    }

    /**
     * Builds the entry.
     *
     * @return the entry
     * @throws IllegalStateException if it was given neither a destination nor a subtree
     */
    public DestinationEntry<D> build() {
      if (destination == null && subtree == null) {
        throw new IllegalStateException(
            "A destination entry needs a destination, a subtree, or both");
      }
      return new DestinationEntry<>(destination, subtree, display);
    }
  }
}
