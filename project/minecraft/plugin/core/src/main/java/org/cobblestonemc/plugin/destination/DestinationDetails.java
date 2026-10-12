/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.destination;

import java.util.List;
import net.kyori.adventure.text.Component;
import org.cobblestonemc.plugin.api.MinecraftDestination;

/** Builders for {@link MinecraftDestination#detail()} text, so every platform words it alike. */
public final class DestinationDetails {

  private DestinationDetails() {}

  /**
   * A block position and its world, e.g. {@code -120, 64, 300 (minecraft:overworld)} — enough for a
   * player to get there by hand.
   *
   * @param x the block x-coordinate
   * @param y the block y-coordinate
   * @param z the block z-coordinate
   * @param world the world's namespaced key
   * @return the detail
   */
  public static Component coordinates(int x, int y, int z, String world) {
    return Component.text(x + ", " + y + ", " + z + " (" + world + ")");
  }

  /**
   * The hover text for command feedback about a destination: its address, and beneath it the
   * destination's own {@linkplain NavigationTarget#detail() detail}, if it has any.
   *
   * @param address the destination's canonical address
   * @param destination the destination
   * @return the hover text
   */
  public static Component hover(List<String> address, NavigationTarget destination) {
    Component hover = Component.text(String.join(" ", address));
    return destination
        .detail()
        .map(detail -> hover.append(Component.newline()).append(detail))
        .orElse(hover);
  }
}
