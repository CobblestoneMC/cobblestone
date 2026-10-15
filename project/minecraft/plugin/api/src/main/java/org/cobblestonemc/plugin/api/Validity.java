/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.api;

import java.util.Objects;
import java.util.function.Function;
import net.kyori.adventure.text.Component;

/**
 * What {@linkplain DestinationLeaf#build building} a leaf produced: the destination, or why there
 * is none to navigate to right now — e.g. {@code surface} while the player is already out in the
 * open, or {@code death} for a player with no recorded death. An invalid leaf is still offered in
 * tab-completion; picking it shows the player its reason instead of starting a search.
 *
 * @param <D> the destination type
 */
public sealed interface Validity<D> {

  /**
   * The leaf can be navigated to, here.
   *
   * @param destination the destination
   * @param <D> the destination type
   * @return the valid result
   */
  static <D> Validity<D> valid(D destination) {
    return new Valid<>(destination);
  }

  /**
   * The leaf cannot be navigated to right now.
   *
   * @param reason the message shown to the player, as is — so it should be complete, styled text
   * @param <D> the destination type
   * @return the invalid result
   */
  static <D> Validity<D> invalid(Component reason) {
    return new Invalid<>(reason);
  }

  /**
   * Returns this result with its destination, if it has one, transformed.
   *
   * @param mapper transforms the destination
   * @param <E> the new destination type
   * @return the transformed result
   */
  <E> Validity<E> map(Function<? super D, ? extends E> mapper);

  /**
   * The leaf can be navigated to.
   *
   * @param destination the destination
   * @param <D> the destination type
   */
  record Valid<D>(D destination) implements Validity<D> {

    /** Canonical constructor; the destination is required. */
    public Valid {
      Objects.requireNonNull(destination, "destination");
    }

    @Override
    public <E> Validity<E> map(Function<? super D, ? extends E> mapper) {
      return new Valid<>(mapper.apply(destination));
    }
  }

  /**
   * The leaf cannot be navigated to right now.
   *
   * @param reason the message shown to the player
   * @param <D> the destination type
   */
  record Invalid<D>(Component reason) implements Validity<D> {

    /** Canonical constructor; the reason is required. */
    public Invalid {
      Objects.requireNonNull(reason, "reason");
    }

    @Override
    public <E> Validity<E> map(Function<? super D, ? extends E> mapper) {
      return new Invalid<>(reason);
    }
  }
}
