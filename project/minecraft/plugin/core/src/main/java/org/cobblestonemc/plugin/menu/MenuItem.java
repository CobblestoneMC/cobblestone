/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.menu;

import java.util.List;
import net.kyori.adventure.text.Component;
import org.cobblestonemc.plugin.api.Icon;

/**
 * One item of the navigation menu, fully worded and styled: all a platform has left to do is make
 * {@code icon} into an item stack and put the name and lore on it.
 *
 * @param icon the item
 * @param name the item's name
 * @param lore the lines beneath the name
 */
public record MenuItem(Icon icon, Component name, List<Component> lore) {
  /** Canonical constructor; copies the lore. */
  public MenuItem {
    lore = List.copyOf(lore);
  }
}
