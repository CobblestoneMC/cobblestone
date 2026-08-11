/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper;

import net.whimxiqal.odyssey.DomainRegion;
import net.whimxiqal.odyssey.Position;
import net.whimxiqal.odyssey.Transition;
import net.whimxiqal.odyssey.api.TraversalState;
import net.whimxiqal.odyssey.minecraft.MinecraftWorld;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepPayload;
import net.whimxiqal.odyssey.minecraft.api.PlatformTransition;
import net.whimxiqal.odyssey.minecraft.api.WorldRegion;
import org.bukkit.Location;
import org.bukkit.World;
import org.joml.Vector3i;

/**
 * Adapts a platform {@link PlatformTransition} (Bukkit region/location) into a core {@link
 * Transition} (domain region/position) for the search. One is created per provider-supplied
 * transition at the start of each search.
 */
final class PaperTransitionAdapter implements Transition<MinecraftStepPayload, MinecraftWorld> {

  private final PlatformTransition<WorldRegion<World, Vector3i>, Location> delegate;
  private final DomainRegion<MinecraftWorld> origin;
  private final Position<MinecraftWorld> destination;

  PaperTransitionAdapter(
      PlatformTransition<WorldRegion<World, Vector3i>, Location> delegate,
      WorldWrapper worldWrapper) {
    this.delegate = delegate;
    WorldRegion<World, Vector3i> originLocation = delegate.origin();
    Location destinationLocation = delegate.destination();
    this.origin = PaperConversions.region(originLocation, worldWrapper);
    this.destination =
        new Position<>(
            PaperConversions.cell(destinationLocation),
            worldWrapper.wrap(destinationLocation.getWorld()));
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
