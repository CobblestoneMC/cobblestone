/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.whimxiqal.odyssey.api.Path;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepPayload;
import net.whimxiqal.odyssey.paper.plugin.api.NavigatorFactory;
import net.whimxiqal.odyssey.paper.plugin.api.TrailNavigatorSettings;
import net.whimxiqal.odyssey.plugin.api.Navigator;
import net.whimxiqal.odyssey.plugin.api.NavigatorSettings;
import net.whimxiqal.odyssey.plugin.config.ConfigKeys;
import net.whimxiqal.odyssey.plugin.config.ConfigManager;
import net.whimxiqal.odyssey.plugin.message.Messages;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;

/**
 * The built-in {@link NavigatorFactory} (key {@code trail}) that creates {@link TrailNavigator}s.
 * Odyssey registers it as a Bukkit service so it is discovered like any third-party navigator.
 * Appearance (buffer, colors, density) is read from config on each creation, so a reload applies to
 * subsequently started trips.
 */
public final class PaperTrailNavigatorFactory implements NavigatorFactory {

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
  public Navigator<Location> create(
      Player player, Path<Location, MinecraftStepPayload> path, NavigatorSettings settings) {
    // Per-trip settings override the config defaults; anything unset falls back to config.
    List<Particle> particles =
        settings
            .get(TrailNavigatorSettings.PARTICLES)
            .orElseGet(() -> parseParticles(config.get(keys.trailParticles)));
    List<Color> colors =
        settings
            .get(TrailNavigatorSettings.COLORS)
            .orElseGet(() -> parseColors(config.get(keys.trailColors)));
    return new TrailNavigator(
        player,
        path,
        config.get(keys.trailBufferCells),
        particles,
        colors,
        config.get(keys.trailDensity),
        config.get(keys.tripsRecalculateDistance),
        messages);
  }

  /** Parses Bukkit {@code Particle} names, skipping unknown ones. */
  private static List<Particle> parseParticles(List<String> names) {
    List<Particle> particles = new ArrayList<>();
    for (String name : names) {
      try {
        particles.add(Particle.valueOf(name.trim().toUpperCase(Locale.ROOT)));
      } catch (IllegalArgumentException ignored) {
        // Skip an unknown particle name; TrailNavigator falls back to DUST if none are valid.
      }
    }
    return particles;
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
