/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper.plugin;

import java.util.ArrayList;
import java.util.List;
import net.whimxiqal.odyssey.api.Path;
import net.whimxiqal.odyssey.api.Step;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepPayload;
import net.whimxiqal.odyssey.paper.plugin.api.PaperNavigatorFactory;
import net.whimxiqal.odyssey.plugin.api.Navigator;
import net.whimxiqal.odyssey.plugin.api.NavigatorContext;
import net.whimxiqal.odyssey.plugin.config.ConfigKeys;
import net.whimxiqal.odyssey.plugin.config.ConfigManager;
import net.whimxiqal.odyssey.plugin.message.Messages;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * The built-in {@link PaperNavigatorFactory} (key {@code trail}) that creates {@link TrailNavigator}s.
 * Odyssey registers it as a Bukkit service so it is discovered like any third-party navigator.
 * Appearance (buffer, colors, density) is read from config on each creation, so a reload applies to
 * subsequently started trips.
 */
public final class PaperTrailNavigatorFactory implements PaperNavigatorFactory {

  /** The navigator id, matched by {@code /navigate -navigator trail}. */
  public static final String KEY = "trail";

  private final ConfigManager config;
  private final ConfigKeys keys;
  private final Messages messages;

  /**
   * Creates the factory.
   *
   * @param config the config manager (read live for trail appearance)
   * @param keys the registered config keys
   * @param messages the message renderer (for action prompts)
   */
  public PaperTrailNavigatorFactory(ConfigManager config, ConfigKeys keys, Messages messages) {
    this.config = config;
    this.keys = keys;
    this.messages = messages;
  }

  @Override
  public String key() {
    return KEY;
  }

  @Override
  public Navigator<Location> create(
      Player player,
      Path<Step<Location, MinecraftStepPayload>> path,
      NavigatorContext<Player> context) {
    return new TrailNavigator(player, path,
        config.get(keys.trailBufferCells),
        parseColors(config.get(keys.trailColors)),
        config.get(keys.trailDensity),
        config.get(keys.tripsAbandonDistance),
        messages);
  }

  /** Parses hex {@code RRGGBB} strings to colors, skipping malformed entries. */
  private static List<Color> parseColors(List<String> hexes) {
    List<Color> colors = new ArrayList<>();
    for (String hex : hexes) {
      try {
        colors.add(Color.fromRGB(Integer.parseInt(hex.trim(), 16)));
      } catch (RuntimeException ignored) {
        // Skip a malformed color; TrailNavigator falls back to a default if none are valid.
      }
    }
    return colors;
  }
}
