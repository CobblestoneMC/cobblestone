/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.sponge12;

import net.whimxiqal.odyssey.Cell;
import net.whimxiqal.odyssey.DomainRegion;
import net.whimxiqal.odyssey.Position;
import net.whimxiqal.odyssey.minecraft.MinecraftWorld;
import net.whimxiqal.odyssey.minecraft.api.WorldRegion;
import org.spongepowered.api.ResourceKey;
import org.spongepowered.api.world.server.ServerLocation;
import org.spongepowered.api.world.server.ServerWorld;
import org.spongepowered.math.vector.Vector3i;

/**
 * Static conversions between Sponge's {@link ServerLocation} and Odyssey's internal {@link Cell}/
 * {@link Position}, so the Sponge façade can speak native types on its surface while the core
 * speaks its own.
 */
public final class SpongeConversions {

  private SpongeConversions() {}

  static DomainRegion<MinecraftWorld> region(
      WorldRegion<ServerWorld, Vector3i> region, WorldWrapper wrapper) {
    return new DomainRegion.Impl<>(
        wrapper.wrap(region.world()),
        cell -> region.contains(vector(cell)),
        cell -> cell(region.nearestBoundaryLocation(vector(cell))),
        region::toString);
  }

  /**
   * Returns the block-coordinate {@link Cell} of a location (its domain is carried separately).
   *
   * @param location the location
   * @return the cell
   */
  static Cell cell(ServerLocation location) {
    return new Cell(location.blockX(), location.blockY(), location.blockZ());
  }

  static Cell cell(Vector3i vector) {
    return new Cell(vector.x(), vector.y(), vector.z());
  }

  /**
   * Rebuilds a native {@link ServerLocation} from a core {@link Position}, re-resolving the world
   * by its namespaced key. Uses the key-based factory so it does not pin a world object.
   *
   * @param position the position
   * @return the location at the block of the position's cell
   */
  static ServerLocation location(Position<MinecraftWorld> position) {
    Cell cell = position.cell();
    return ServerLocation.of(
        ResourceKey.resolve(position.domain().key()), cell.x(), cell.y(), cell.z());
  }

  static Vector3i vector(Cell cell) {
    return new Vector3i(cell.x(), cell.y(), cell.z());
  }
}
