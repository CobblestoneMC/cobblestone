/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper.plugin.api;

import java.util.List;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.whimxiqal.odyssey.api.Destination;
import net.whimxiqal.odyssey.minecraft.api.WorldRegion;
import net.whimxiqal.odyssey.paper.api.SingleCellWorldRegion;
import net.whimxiqal.odyssey.plugin.api.MinecraftDestination;
import org.bukkit.Location;
import org.bukkit.World;
import org.joml.Vector3i;

/**
 * Factories for {@link MinecraftDestination}s with Paper's world/vector types already bound — the
 * destination counterpart to {@link net.whimxiqal.odyssey.paper.api.PaperTransition}, so
 * integrations never echo the {@code WorldRegion<World, Vector3i>} generics. The region supplier is
 * re-evaluated each search, so a moving target (a home, a town's claims) stays current.
 *
 * <p>Navigability is gated by Odyssey's {@code odyssey.navigate.*} permission; a destination's own
 * {@link MinecraftDestination#permissions()} is reserved for genuine hard requirements, so these
 * default to none.
 */
public final class PaperDestination {

  private PaperDestination() {}

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
      Destination<WorldRegion<World, Vector3i>> destination,
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
