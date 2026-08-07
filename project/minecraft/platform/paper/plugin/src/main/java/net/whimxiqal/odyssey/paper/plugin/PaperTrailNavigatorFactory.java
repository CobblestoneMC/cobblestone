/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper.plugin;

import net.whimxiqal.odyssey.api.Path;
import net.whimxiqal.odyssey.api.Step;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepPayload;
import net.whimxiqal.odyssey.paper.plugin.api.PaperNavigatorFactory;
import net.whimxiqal.odyssey.plugin.api.Navigator;
import net.whimxiqal.odyssey.plugin.api.NavigatorContext;
import net.whimxiqal.odyssey.plugin.message.Messages;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * The built-in {@link PaperNavigatorFactory} (key {@code trail}) that creates {@link TrailNavigator}s.
 * Odyssey registers it as a Bukkit service so it is discovered like any third-party navigator.
 */
public final class PaperTrailNavigatorFactory implements PaperNavigatorFactory {

  /** The navigator id, matched by {@code /navigate -navigator trail}. */
  public static final String KEY = "trail";

  private final int bufferCells;
  private final Messages messages;

  /**
   * Creates the factory.
   *
   * @param bufferCells how many cells ahead the trail renders
   * @param messages the message renderer (for action prompts)
   */
  public PaperTrailNavigatorFactory(int bufferCells, Messages messages) {
    this.bufferCells = bufferCells;
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
    return new TrailNavigator(player, path, bufferCells, messages);
  }
}
