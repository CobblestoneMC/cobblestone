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
import org.cobblestonemc.plugin.api.Icon;
import org.cobblestonemc.plugin.config.ConfigKeys;
import org.cobblestonemc.plugin.config.ConfigManager;
import org.cobblestonemc.plugin.config.MenuIcons;

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
        MiniMessage.miniMessage().deserialize(config.get(keys.menuTitle)),
        Icon.parse(config.get(keys.menuFiller), Icon.item(MenuIcons.FILLER_DEFAULT)),
        config.get(keys.menuRecents),
        MenuIcons.reader(config, keys.menuIcons));
  }
}
