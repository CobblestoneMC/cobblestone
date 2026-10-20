/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.menu;

import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.ParsingException;
import org.cobblestonemc.plugin.api.Icon;
import org.cobblestonemc.plugin.config.ConfigKeys;
import org.cobblestonemc.plugin.config.ConfigManager;

/**
 * The admin's choices for how the navigation menu looks, read afresh each time it opens so a reload
 * takes effect at once.
 *
 * @param title the menu's title
 * @param filler the item in the border and gaps
 * @param recents whether to show recent destinations
 * @param icons looks up a {@linkplain MenuIcons configurable item} by name
 */
public record MenuSettings(
    Component title, Icon filler, boolean recents, Function<String, Icon> icons) {

  /**
   * Reads the settings from the config.
   *
   * @param config the config
   * @param keys its keys
   * @return the settings
   */
  public static MenuSettings from(ConfigManager config, ConfigKeys keys) {
    return new MenuSettings(
        title(config.get(keys.menuTitle)),
        Icon.parse(config.get(keys.menuFiller))
            .orElse(Icon.item("minecraft:light_gray_stained_glass_pane")),
        config.get(keys.menuRecents),
        MenuIcons.reader(config, keys.menuIcons));
  }

  /** The title as MiniMessage, or as it was written if it is not valid MiniMessage. */
  static Component title(String text) {
    try {
      return MiniMessage.miniMessage().deserialize(text);
    } catch (ParsingException e) {
      return Component.text(text);
    }
  }
}
