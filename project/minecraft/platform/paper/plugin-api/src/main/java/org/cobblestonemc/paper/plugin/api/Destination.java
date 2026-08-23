/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper.plugin.api;

import java.util.List;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.World;
import org.cobblestonemc.minecraft.api.WorldRegion;
import org.cobblestonemc.paper.api.SingleCellWorldRegion;
import org.cobblestonemc.paper.api.Transition;
import org.cobblestonemc.plugin.api.MinecraftDestination;
import org.joml.Vector3i;

/**
 * Factories for {@link MinecraftDestination}s with Paper's world/vector types already bound — the
 * destination counterpart to {@link Transition}, so integrations never echo the {@code
 * WorldRegion<World, Vector3i>} generics. The region supplier is re-evaluated each search, so a
 * moving target (a home, a town's claims) stays current.
 *
 * <p>Navigability is gated by Cobblestone's {@code cobblestone.navigate.*} permission; a
 * destination's own {@link MinecraftDestination#permissions()} is reserved for genuine hard
 * requirements, so these default to none.
 */
public final class Destination {

  private Destination() {}

  /**
   * A destination at a single location (its block cell), or an empty one if {@code location} is
   * null.
   */
  public static MinecraftDestination<World, Vector3i> at(Location location, String name) {
    return regions(
        location == null || location.getWorld() == null
            ? List::of
            : () -> List.of(SingleCellWorldRegion.of(location)),
        name);
  }

  /** A destination covering one region. */
  public static MinecraftDestination<World, Vector3i> region(
      WorldRegion<World, Vector3i> region, String name) {
    return regions(() -> List.of(region), name);
  }

  /**
   * A destination covering a set of regions, re-resolved each search (the nearest one is the goal).
   */
  public static MinecraftDestination<World, Vector3i> regions(
      Supplier<List<WorldRegion<World, Vector3i>>> regions, String name) {
    return new Impl(regions::get, Component.text(name), List.of(), false);
  }

  private record Impl(
      org.cobblestonemc.api.Destination<WorldRegion<World, Vector3i>> destination,
      Component displayName,
      List<String> permissions,
      boolean mobile)
      implements MinecraftDestination<World, Vector3i> {

    @Override
    public boolean isMobile() {
      return mobile;
    }
  }
}
