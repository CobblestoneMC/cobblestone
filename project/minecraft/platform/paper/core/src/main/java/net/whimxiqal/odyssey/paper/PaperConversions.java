/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper;

import net.whimxiqal.odyssey.Cell;
import net.whimxiqal.odyssey.DomainRegion;
import net.whimxiqal.odyssey.Position;
import net.whimxiqal.odyssey.minecraft.MinecraftWorld;
import net.whimxiqal.odyssey.minecraft.api.WorldRegion;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.joml.Vector3i;

/**
 * Static conversions between Bukkit's {@link Location} and Odyssey's internal {@link Cell}/
 * {@link Position}, so the Paper façade can speak native types on its surface while the core speaks
 * its own.
 */
final class PaperConversions {

  private PaperConversions() {
  }

  static DomainRegion<MinecraftWorld> region(WorldRegion<World, Vector3i> region, WorldWrapper wrapper) {
    return new DomainRegion.Impl<>(wrapper.wrap(region.world()),
            cell -> region.contains(vector(cell)),
            cell -> cell(region.nearestBoundaryLocation(vector(cell))));
  }

  /**
   * Returns the block-coordinate {@link Cell} of a location (its domain is carried separately).
   *
   * @param location the location
   * @return the cell
   */
  static Cell cell(Location location) {
    return new Cell(location.getBlockX(), location.getBlockY(), location.getBlockZ());
  }

  static Cell cell(Vector3i vector) {
    return new Cell(vector.x, vector.y, vector.z);
  }

  /**
   * Rebuilds a native {@link Location} from a core {@link Position}, re-resolving the Bukkit world
   * by its namespaced key. The world may be {@code null} if it has since unloaded.
   *
   * @param position the position
   * @return the location at the block center of the position's cell
   */
  static Location location(Position<MinecraftWorld> position) {
    NamespacedKey key = NamespacedKey.fromString(position.domain().key());
    World world = key == null ? null : Bukkit.getWorld(key);
    Cell cell = position.cell();
    return new Location(world, cell.x(), cell.y(), cell.z());
  }

  static Vector3i vector(Cell cell) {
    return new Vector3i(cell.x(), cell.y(), cell.z());
  }
}
