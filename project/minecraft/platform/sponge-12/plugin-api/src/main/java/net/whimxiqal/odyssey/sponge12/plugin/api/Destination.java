/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.sponge12.plugin.api;

import java.util.List;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.whimxiqal.odyssey.minecraft.api.WorldRegion;
import net.whimxiqal.odyssey.plugin.api.MinecraftDestination;
import net.whimxiqal.odyssey.sponge12.api.SingleCellWorldRegion;
import net.whimxiqal.odyssey.sponge12.api.Transition;
import org.spongepowered.api.world.server.ServerLocation;
import org.spongepowered.api.world.server.ServerWorld;
import org.spongepowered.math.vector.Vector3i;

/**
 * Factories for {@link MinecraftDestination}s with Sponge's world/vector types already bound — the
 * destination counterpart to {@link Transition}, so integrations never echo the {@code
 * WorldRegion<ServerWorld, Vector3i>} generics. The region supplier is re-evaluated each search, so
 * a moving target (a home, a town's claims) stays current.
 *
 * <p>Navigability is gated by Odyssey's {@code odyssey.navigate.*} permission; a destination's own
 * {@link MinecraftDestination#permissions()} is reserved for genuine hard requirements, so these
 * default to none.
 */
public final class Destination {

  private Destination() {}

  /** A destination at a single location (its block cell), or empty if {@code location} is null. */
  public static MinecraftDestination<ServerWorld, Vector3i> at(
      ServerLocation location, String name) {
    return regions(
        location == null ? List::of : () -> List.of(SingleCellWorldRegion.of(location)), name);
  }

  /** A destination covering one region. */
  public static MinecraftDestination<ServerWorld, Vector3i> region(
      WorldRegion<ServerWorld, Vector3i> region, String name) {
    return regions(() -> List.of(region), name);
  }

  /**
   * A destination covering a set of regions, re-resolved each search (the nearest one is the goal).
   */
  public static MinecraftDestination<ServerWorld, Vector3i> regions(
      Supplier<List<WorldRegion<ServerWorld, Vector3i>>> regions, String name) {
    return new Impl(regions::get, Component.text(name), List.of(), false);
  }

  private record Impl(
      net.whimxiqal.odyssey.api.Destination<WorldRegion<ServerWorld, Vector3i>> destination,
      Component displayName,
      List<String> permissions,
      boolean mobile)
      implements MinecraftDestination<ServerWorld, Vector3i> {

    @Override
    public boolean isMobile() {
      return mobile;
    }
  }
}
