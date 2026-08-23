/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge12;

import org.cobblestonemc.DomainRegion;
import org.cobblestonemc.Position;
import org.cobblestonemc.Transition;
import org.cobblestonemc.api.TraversalState;
import org.cobblestonemc.minecraft.MinecraftWorld;
import org.cobblestonemc.minecraft.api.MinecraftStepPayload;
import org.cobblestonemc.minecraft.api.PlatformTransition;
import org.cobblestonemc.minecraft.api.WorldRegion;
import org.spongepowered.api.world.server.ServerLocation;
import org.spongepowered.api.world.server.ServerWorld;
import org.spongepowered.math.vector.Vector3i;

/**
 * Adapts a platform {@link PlatformTransition} (Sponge region/location) into a core {@link
 * Transition} (domain region/position) for the search. One is created per provider-supplied
 * transition at the start of each search.
 */
final class SpongeTransitionAdapter implements Transition<MinecraftStepPayload, MinecraftWorld> {

  private final PlatformTransition<WorldRegion<ServerWorld, Vector3i>, ServerLocation> delegate;
  private final DomainRegion<MinecraftWorld> origin;
  private final Position<MinecraftWorld> destination;

  SpongeTransitionAdapter(
      PlatformTransition<WorldRegion<ServerWorld, Vector3i>, ServerLocation> delegate,
      WorldWrapper worldWrapper) {
    this.delegate = delegate;
    ServerLocation destinationLocation = delegate.destination();
    this.origin = SpongeConversions.region(delegate.origin(), worldWrapper);
    this.destination =
        new Position<>(
            SpongeConversions.cell(destinationLocation),
            worldWrapper.wrap(destinationLocation.world()));
  }

  @Override
  public DomainRegion<MinecraftWorld> origin() {
    return origin;
  }

  @Override
  public Position<MinecraftWorld> destination() {
    return destination;
  }

  @Override
  public double cost() {
    return delegate.cost();
  }

  @Override
  public double time() {
    return delegate.time();
  }

  @Override
  public MinecraftStepPayload payload() {
    return delegate.payload();
  }

  @Override
  public TraversalState apply(TraversalState in) {
    return delegate.apply(in);
  }
}
