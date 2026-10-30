/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.menu;

import java.util.List;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import org.cobblestonemc.plugin.api.Icon;

/**
 * One button of the navigation menu {@linkplain NavigationMenu#list listed}, as on a Bedrock form.
 *
 * @param name what it says
 * @param detail a line beneath, if it has one
 * @param pictures the items to picture it with, best first, for a platform that cannot picture
 *     every item (a Bedrock form, for a player head): its own item, then the configured folder or
 *     destination item. Empty for a button with no picture, such as back.
 */
public record MenuButton(Component name, Optional<Component> detail, List<Icon> pictures) {
  /** Canonical constructor; copies the pictures. */
  public MenuButton {
    pictures = List.copyOf(pictures);
  }
}
