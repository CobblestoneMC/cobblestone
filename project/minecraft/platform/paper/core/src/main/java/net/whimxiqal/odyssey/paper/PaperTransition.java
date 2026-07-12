/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper;

import java.util.function.Function;
import net.whimxiqal.odyssey.core.CellRegion;
import net.whimxiqal.odyssey.api.DomainRegion;
import net.whimxiqal.odyssey.api.Position;
import net.whimxiqal.odyssey.api.TraversalState;
import net.whimxiqal.odyssey.api.Transition;
import net.whimxiqal.odyssey.minecraft.api.MinecraftInstruction;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepType;
import net.whimxiqal.odyssey.minecraft.api.MinecraftWorld;
import net.whimxiqal.odyssey.minecraft.api.PlatformSingleCellTransition;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * Adapts a developer-supplied {@link PlatformSingleCellTransition} (in native {@link Location}
 * terms) into a core {@link Transition}: the single origin location becomes a one-cell region and
 * the destination location becomes a {@link Position}. Endpoints are resolved eagerly at
 * construction so the core never re-does the lookup.
 */
final class PaperTransition implements Transition<MinecraftStepType, MinecraftInstruction, MinecraftWorld> {

  private final PlatformSingleCellTransition<Location> delegate;
  private final DomainRegion<MinecraftWorld> origin;
  private final Position<MinecraftWorld> destination;

  PaperTransition(
      PlatformSingleCellTransition<Location> delegate, Function<World, MinecraftWorld> worldWrapper) {
    this.delegate = delegate;
    Location originLocation = delegate.origin();
    Location destinationLocation = delegate.destination();
    this.origin = new CellRegion<>(
        PaperConversions.cell(originLocation), worldWrapper.apply(originLocation.getWorld()));
    this.destination = new Position<>(
        PaperConversions.cell(destinationLocation), worldWrapper.apply(destinationLocation.getWorld()));
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
  public MinecraftStepType stepType() {
    return delegate.stepType();
  }

  @Override
  public MinecraftInstruction instruction() {
    return delegate.instruction();
  }

  @Override
  public TraversalState apply(TraversalState in) {
    return delegate.apply(in);
  }
}
