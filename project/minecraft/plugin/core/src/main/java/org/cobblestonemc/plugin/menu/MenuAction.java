/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.menu;

import java.util.List;
import net.kyori.adventure.text.Component;
import org.cobblestonemc.plugin.api.DestinationLeaf;
import org.cobblestonemc.plugin.destination.DestinationResolver;
import org.cobblestonemc.plugin.destination.NavigationTarget;

/** What a platform should do after a click in the navigation menu. */
public sealed interface MenuAction {

  /** The click landed on nothing that does anything. */
  record None() implements MenuAction {}

  /** The menu changed — another level or page — and should be drawn again. */
  record Redraw() implements MenuAction {}

  /** The player closed the menu. */
  record Close() implements MenuAction {}

  /**
   * The player picked a destination: close the menu and navigate, just as {@code /navigate} would
   * have.
   *
   * @param destination the destination, with its options parsed
   */
  record Navigate(DestinationResolver.Resolved<NavigationTarget> destination)
      implements MenuAction {}

  /**
   * The player picked a destination that needs more from them, such as coordinates: close the menu
   * and ask for its options.
   *
   * @param address the destination's canonical address
   * @param destination the destination
   * @param title what to call it while asking
   */
  record FillIn(
      List<String> address, DestinationLeaf<NavigationTarget> destination, Component title)
      implements MenuAction {
    /** Canonical constructor; copies the address. */
    public FillIn {
      address = List.copyOf(address);
    }
  }
}
