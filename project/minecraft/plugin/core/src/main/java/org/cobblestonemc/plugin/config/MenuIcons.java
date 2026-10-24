/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import org.cobblestonemc.plugin.api.Icon;

/**
 * The navigation menu's configurable items: one for each of Cobblestone's own entries, named by the
 * entry's key, and the menu's buttons and fallbacks. Both platforms' built-in destinations read
 * their items through here, so they look alike.
 */
public final class MenuIcons {

  /** The default item in the menu's border and gaps; see {@code menu.filler}. */
  public static final String FILLER_DEFAULT = "minecraft:gray_stained_glass_pane";

  /** Fallback for a destination that chooses no item. */
  public static final String DESTINATION = "destination";

  /** Fallback for an entry that only opens a further level and chooses no item. */
  public static final String FOLDER = "folder";

  /** The button that returns to the previous level. */
  public static final String BACK = "back";

  /** The buttons that turn the page. */
  public static final String PAGE = "page";

  /** The button that closes the menu. */
  public static final String CLOSE = "close";

  /** The item at the top that says where in the tree the player is. */
  public static final String BREADCRUMB = "breadcrumb";

  /** Cobblestone's own entry at the top level. */
  public static final String COBBLESTONE = "cobblestone";

  /** The player's last death. */
  public static final String DEATH = "death";

  /** Anywhere open to the sky. */
  public static final String SURFACE = "surface";

  /** The block the player is looking at. */
  public static final String TARGET = "target";

  /** The world's spawn. */
  public static final String SPAWN = "spawn";

  /** The player's respawn point. */
  public static final String RESPAWN = "respawn";

  /** The player's last mount. */
  public static final String MOUNT = "mount";

  /** The level of other players. */
  public static final String PLAYER = "player";

  /** The level of worlds. */
  public static final String WORLD = "world";

  /** A world of the overworld kind. */
  public static final String WORLD_OVERWORLD = "world_overworld";

  /** A world of the nether kind. */
  public static final String WORLD_NETHER = "world_nether";

  /** A world of the end kind. */
  public static final String WORLD_END = "world_end";

  /** The level of saved locations. */
  public static final String LOCATION = "location";

  /** The server-wide saved locations. */
  public static final String GLOBAL = "global";

  /** The player's own saved locations. */
  public static final String PRIVATE = "private";

  /** One saved location. */
  public static final String SAVED_LOCATION = "saved_location";

  /** The level of the player's pets. */
  public static final String PET = "pet";

  /** The level of destinations given by coordinates. */
  public static final String COORDINATES = "coordinates";

  /** One block, by its coordinates. */
  public static final String BLOCK = "block";

  /** A configurable item's default, and what the config file says about it. */
  public record Default(String item, String description) {}

  /** Every configurable item, in the order the config file lists them. */
  public static final Map<String, Default> DEFAULTS = defaults();

  private MenuIcons() {}

  private static Map<String, Default> defaults() {
    Map<String, Default> defaults = new LinkedHashMap<>();
    defaults.put(
        DESTINATION, new Default("minecraft:ender_eye", "A destination that chooses no item."));
    defaults.put(
        FOLDER,
        new Default("minecraft:chest", "An entry with more inside it that chooses no item."));
    defaults.put(BACK, new Default("minecraft:arrow", "The button that goes back a level."));
    defaults.put(PAGE, new Default("minecraft:paper", "The buttons that turn the page."));
    defaults.put(CLOSE, new Default("minecraft:barrier", "The button that closes the menu."));
    defaults.put(
        BREADCRUMB,
        new Default(
            "minecraft:nether_star", "The item at the top naming where in the menu you are."));
    defaults.put(
        COBBLESTONE, new Default("minecraft:cobblestone", "Cobblestone's own destinations."));
    defaults.put(DEATH, new Default("minecraft:skeleton_skull", "Your last death."));
    defaults.put(SURFACE, new Default("minecraft:grass_block", "The nearest open sky."));
    defaults.put(TARGET, new Default("minecraft:spyglass", "The block you are looking at."));
    defaults.put(SPAWN, new Default("minecraft:beacon", "The world's spawn."));
    defaults.put(RESPAWN, new Default("minecraft:red_bed", "Your respawn point."));
    defaults.put(MOUNT, new Default("minecraft:saddle", "Your last mount."));
    defaults.put(PLAYER, new Default("minecraft:player_head", "Other players."));
    defaults.put(WORLD, new Default("minecraft:filled_map", "The other worlds."));
    defaults.put(WORLD_OVERWORLD, new Default("minecraft:oak_sapling", "An overworld."));
    defaults.put(WORLD_NETHER, new Default("minecraft:netherrack", "A nether."));
    defaults.put(WORLD_END, new Default("minecraft:end_stone", "An end."));
    defaults.put(LOCATION, new Default("minecraft:writable_book", "Saved locations."));
    defaults.put(GLOBAL, new Default("minecraft:lectern", "The server's saved locations."));
    defaults.put(PRIVATE, new Default("minecraft:ender_chest", "Your own saved locations."));
    defaults.put(SAVED_LOCATION, new Default("minecraft:oak_sign", "One saved location."));
    defaults.put(PET, new Default("minecraft:bone", "Your pets."));
    defaults.put(
        COORDINATES, new Default("minecraft:compass", "Destinations given by coordinates."));
    defaults.put(BLOCK, new Default("minecraft:stone", "One block, by its coordinates."));
    return Collections.unmodifiableMap(defaults);
  }

  /**
   * Reads the configured items, falling back to each default when an admin left one blank.
   *
   * @param config the config
   * @param keys the registered item keys, as {@code ConfigKeys#menuIcons}
   * @return looks up an item by its name here
   */
  public static Function<String, Icon> reader(
      ConfigManager config, Map<String, ConfigKey<String>> keys) {
    return name -> {
      ConfigKey<String> key = keys.get(name);
      return Icon.parse(key == null ? null : config.get(key), Icon.item(DEFAULTS.get(name).item()));
    };
  }
}
