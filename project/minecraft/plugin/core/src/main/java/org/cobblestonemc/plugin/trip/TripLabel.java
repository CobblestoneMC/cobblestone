/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.trip;

import java.util.List;
import java.util.Objects;

/**
 * What a trip is called, and the destination address it was built from, if any.
 *
 * @param text the full label text
 * @param address the canonical destination address, or empty for a caller-supplied label
 */
public record TripLabel(String text, List<String> address) {

  public TripLabel {
    Objects.requireNonNull(text, "text");
    address = List.copyOf(address);
  }

  /**
   * A caller-supplied label, shown as-is.
   *
   * @param text the label
   * @return the label
   */
  public static TripLabel of(String text) {
    return new TripLabel(text, List.of());
  }

  /**
   * A label for a resolved destination address.
   *
   * @param address the canonical address (non-empty)
   * @return the label
   */
  public static TripLabel address(List<String> address) {
    if (address.isEmpty()) {
      throw new IllegalArgumentException("address must not be empty");
    }
    return new TripLabel(String.join(" ", address), address);
  }
}
