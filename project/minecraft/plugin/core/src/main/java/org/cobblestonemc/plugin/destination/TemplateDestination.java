/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.destination;

import java.util.List;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import org.cobblestonemc.api.Destination;
import org.cobblestonemc.minecraft.api.WorldRegion;
import org.cobblestonemc.plugin.api.DestinationOption;
import org.cobblestonemc.plugin.api.MinecraftDestination;
import org.cobblestonemc.plugin.api.OptionValues;

/**
 * A {@link MinecraftDestination} that takes {@linkplain DestinationOption options} and only becomes
 * a real destination once they are applied — e.g. {@code coordinates block <x> <y> <z> [world]}.
 * Its own {@link #destination()} is empty; the command searches what {@link #configure} returns.
 *
 * @param displayName the human-facing name
 * @param permissions the permission nodes required to use it (all of them); empty for unrestricted
 * @param options the options, required ones first
 * @param configurer builds the concrete destination from the parsed option values
 * @param <W> the platform world type
 * @param <V> the platform vector type
 */
public record TemplateDestination<W, V>(
    Component displayName,
    List<String> permissions,
    List<DestinationOption<?>> options,
    Function<OptionValues, MinecraftDestination<W, V>> configurer)
    implements MinecraftDestination<W, V> {

  /**
   * Canonical constructor; copies the lists and checks the required options come first.
   *
   * @throws IllegalArgumentException if a required option follows an optional one
   */
  public TemplateDestination {
    permissions = List.copyOf(permissions);
    options = List.copyOf(options);
    boolean optionalSeen = false;
    for (DestinationOption<?> option : options) {
      if (option.required() && optionalSeen) {
        throw new IllegalArgumentException(
            "required option " + option.name() + " follows an optional one");
      }
      optionalSeen |= !option.required();
    }
  }

  @Override
  public Destination<WorldRegion<W, V>> destination() {
    return List::of;
  }

  @Override
  public MinecraftDestination<W, V> configure(OptionValues values) {
    return configurer.apply(values);
  }
}
