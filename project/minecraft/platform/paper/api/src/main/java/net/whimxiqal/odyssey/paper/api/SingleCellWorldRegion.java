/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper.api;

import net.whimxiqal.odyssey.minecraft.api.WorldRegion;
import org.bukkit.Location;
import org.bukkit.World;
import org.joml.Vector3i;

public class SingleCellWorldRegion implements WorldRegion<World, Vector3i> {

    private final Location location;

    public static SingleCellWorldRegion of(Location location) {
        return new SingleCellWorldRegion(location);
    }

    private SingleCellWorldRegion(Location location) {
        this.location = location;
    }

    @Override
    public World world() {
        return location.getWorld();
    }

    @Override
    public boolean contains(Vector3i vector) {
        return location.toVector().toVector3i().equals(vector);
    }

    @Override
    public Vector3i nearestBoundaryLocation(Vector3i vector) {
        return location.toVector().toVector3i();
    }
}
