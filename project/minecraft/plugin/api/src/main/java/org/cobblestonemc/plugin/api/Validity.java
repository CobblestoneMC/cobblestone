/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.api;

import java.util.Objects;
import net.kyori.adventure.text.Component;

/**
 * Whether a {@link MinecraftDestination} can be navigated to right now, and if not, why — e.g.
 * {@code surface} while the player is already out in the open, or {@code death} for a player with
 * no recorded death. An invalid destination is still offered in tab-completion; picking it shows
 * the player its reason instead of starting a search.
 */
public sealed interface Validity {

  /**
   * The destination can be navigated to.
   *
   * @return the valid state
   */
  static Validity valid() {
    return Valid.INSTANCE;
  }

  /**
   * The destination cannot be navigated to right now.
   *
   * @param reason the message shown to the player, as is — so it should be complete, styled text
   * @return the invalid state
   */
  static Validity invalid(Component reason) {
    return new Invalid(reason);
  }

  /**
   * Returns whether this is the valid state.
   *
   * @return {@code true} if the destination can be navigated to
   */
  default boolean isValid() {
    return this instanceof Valid;
  }

  /** The destination can be navigated to. */
  enum Valid implements Validity {
    /** The one valid state. */
    INSTANCE
  }

  /**
   * The destination cannot be navigated to right now.
   *
   * @param reason the message shown to the player
   */
  record Invalid(Component reason) implements Validity {

    /** Canonical constructor; the reason is required. */
    public Invalid {
      Objects.requireNonNull(reason, "reason");
    }
  }
}
