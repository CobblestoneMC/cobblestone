/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.api;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * One named entry in a destination tree: what the {@code /navigate} command needs to know about it
 * up front — who may use it and which {@linkplain DestinationOption options} follow its key — and
 * how to {@linkplain #build build} the destination once a player has actually picked it.
 *
 * <p>Describing and building are kept apart because they happen at very different times.
 * Tab-completion reads the description of every leaf it walks past, on every keystroke and possibly
 * off the server thread, so it must be cheap and must not touch the world. Building happens once,
 * when the command runs, so it may do real work: read the world around the player, look something
 * up, or decide there is nothing to navigate to ({@link Validity#invalid}).
 *
 * @param <D> the destination type
 */
public interface DestinationLeaf<D> {

  /**
   * Returns the permission nodes a player must hold — <b>all</b> of them — to use this leaf. An
   * empty list (the default) means unrestricted.
   *
   * @return the required permissions
   */
  default List<String> permissions() {
    return List.of();
  }

  /**
   * Returns the options this leaf takes after its key, in the order they are typed — e.g. {@code
   * <x> <y> <z> [world]}. Required options come first. Empty (the default) for a leaf that is
   * complete as it stands.
   *
   * @return the options
   */
  default List<DestinationOption<?>> options() {
    return List.of();
  }

  /**
   * Builds the destination, once a player has picked this leaf and may use it. Called once per
   * command, on the thread that owns the player (on Folia, their region), so it may read the world
   * around them. Never called by tab-completion.
   *
   * @param values a value for every required option, and for each optional one that was typed
   * @return the destination, or why it cannot be navigated to right now
   */
  Validity<D> build(OptionValues values);

  /**
   * A leaf without options or permissions that is always valid.
   *
   * @param destination supplies the destination when the leaf is built
   * @param <D> the destination type
   * @return the leaf
   */
  static <D> DestinationLeaf<D> of(Supplier<? extends D> destination) {
    return validated(() -> Validity.valid(destination.get()));
  }

  /**
   * A leaf without options or permissions whose destination may be invalid.
   *
   * @param build builds the destination
   * @param <D> the destination type
   * @return the leaf
   */
  static <D> DestinationLeaf<D> validated(Supplier<Validity<D>> build) {
    return new Simple<>(List.of(), List.of(), values -> build.get());
  }

  /**
   * A leaf with options and no permissions.
   *
   * @param options the options, required ones first
   * @param build builds the destination from the parsed options
   * @param <D> the destination type
   * @return the leaf
   */
  static <D> DestinationLeaf<D> withOptions(
      List<DestinationOption<?>> options, Function<OptionValues, Validity<D>> build) {
    return new Simple<>(List.of(), options, build);
  }

  /**
   * A plain {@link DestinationLeaf} built from its parts.
   *
   * @param permissions the required permissions
   * @param options the options, required ones first
   * @param builder builds the destination from the parsed options
   * @param <D> the destination type
   */
  record Simple<D>(
      List<String> permissions,
      List<DestinationOption<?>> options,
      Function<OptionValues, Validity<D>> builder)
      implements DestinationLeaf<D> {

    /**
     * Canonical constructor; copies the lists and checks the required options come first.
     *
     * @throws IllegalArgumentException if a required option follows an optional one
     */
    public Simple {
      permissions = List.copyOf(permissions);
      options = List.copyOf(options);
      Objects.requireNonNull(builder, "builder");
      boolean optionalSeen = false;
      for (DestinationOption<?> option : options) {
        if (option.required() && optionalSeen) {
          throw new IllegalArgumentException(
              "required option " + option.name() + " follows an optional one");
        }
        optionalSeen |= !option.required();
      }
    }

    @Override
    public Validity<D> build(OptionValues values) {
      return builder.apply(values);
    }
  }
}
