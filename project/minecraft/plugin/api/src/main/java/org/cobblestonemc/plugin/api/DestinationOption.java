/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.api;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.IntSupplier;

/**
 * One argument a destination takes after its key — e.g. the {@code x}, {@code y} and {@code z} of
 * {@code /navigate coordinates block 100 64 -200}. A {@link DestinationLeaf} lists its options in
 * {@link DestinationLeaf#options()}; the command parses one token per option, in order, and hands
 * the values to {@link DestinationLeaf#build(OptionValues)}.
 *
 * <p>Options are never part of a destination's address: they always follow its key, and are never
 * promoted past it. So the address (and the {@code cobblestone.navigate.<address>} permission) of
 * {@code coordinates block 100 64 -200} is just {@code coordinates block}.
 *
 * @param <T> the type a token parses to
 */
public interface DestinationOption<T> {

  /**
   * Returns the option's name, shown in usage hints ({@code <x>}, {@code [world]}).
   *
   * @return the name
   */
  String name();

  /**
   * Returns whether the option must be given. Required options must all come before optional ones
   * in {@link DestinationLeaf#options()}, since tokens are matched to options in order.
   *
   * @return {@code true} if required
   */
  boolean required();

  /**
   * Returns candidate tokens for this option, for tab-completion. The command filters them by what
   * has been typed, so an option may return all of them; {@code partial} is there for an option
   * with too many candidates to list.
   *
   * <p>Like a leaf's description, this runs on every keystroke and possibly off the server thread,
   * so it must be cheap and must not touch the world. Reading the player's own position (for a
   * coordinate hint, say) is fine.
   *
   * @param partial what has been typed of the token so far
   * @return the candidates
   */
  List<String> suggest(String partial);

  /**
   * Parses a typed token.
   *
   * @param token the token
   * @return the value, or empty if the token is not a valid value for this option
   */
  Optional<T> parse(String token);

  /**
   * A required option with the given suggestion and parsing logic.
   *
   * @param name the name
   * @param suggest the tab-completion candidates, given the partial token
   * @param parse parses a token, or returns empty if it is invalid
   * @param <T> the value type
   * @return the option
   */
  static <T> DestinationOption<T> required(
      String name, Function<String, List<String>> suggest, Function<String, Optional<T>> parse) {
    return new Simple<>(name, true, suggest, parse);
  }

  /**
   * An optional option with the given suggestion and parsing logic.
   *
   * @param name the name
   * @param suggest the tab-completion candidates, given the partial token
   * @param parse parses a token, or returns empty if it is invalid
   * @param <T> the value type
   * @return the option
   */
  static <T> DestinationOption<T> optional(
      String name, Function<String, List<String>> suggest, Function<String, Optional<T>> parse) {
    return new Simple<>(name, false, suggest, parse);
  }

  /**
   * A required whole-number option, suggesting a single value — typically the player's own
   * coordinate on that axis, as vanilla commands do.
   *
   * @param name the name
   * @param hint the value to suggest, read when suggestions are asked for (see {@link #suggest})
   * @return the option
   */
  static DestinationOption<Integer> integer(String name, IntSupplier hint) {
    return required(
        name,
        partial -> List.of(Integer.toString(hint.getAsInt())),
        token -> {
          try {
            return Optional.of(Integer.parseInt(token));
          } catch (NumberFormatException e) {
            return Optional.empty();
          }
        });
  }

  /**
   * A plain {@link DestinationOption} built from functions.
   *
   * @param name the name
   * @param required whether it is required
   * @param suggester the tab-completion candidates
   * @param parser the token parser
   * @param <T> the value type
   */
  record Simple<T>(
      String name,
      boolean required,
      Function<String, List<String>> suggester,
      Function<String, Optional<T>> parser)
      implements DestinationOption<T> {

    @Override
    public List<String> suggest(String partial) {
      return suggester.apply(partial);
    }

    @Override
    public Optional<T> parse(String token) {
      return parser.apply(token);
    }
  }
}
