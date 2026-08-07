/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper.plugin;

import net.whimxiqal.odyssey.minecraft.api.MinecraftStepPayload;
import net.whimxiqal.odyssey.minecraft.api.PlatformTransition;
import net.whimxiqal.odyssey.minecraft.api.WorldRegion;
import org.bukkit.Location;
import org.bukkit.World;
import org.joml.Vector3i;

/**
 * A plain {@link PlatformTransition} for a discovered portal: reach the entry {@code origin} region,
 * arrive at {@code destination}. Emitted by {@link PortalTransitionProvider}.
 *
 * @param origin the entry region the agent must reach
 * @param destination the arrival location
 * @param cost the traversal cost in seconds
 * @param payload the step payload (a {@code PORTAL} step)
 */
record PortalPlatformTransition(
    WorldRegion<World, Vector3i> origin,
    Location destination,
    double cost,
    MinecraftStepPayload payload) implements PlatformTransition<WorldRegion<World, Vector3i>, Location> {
}
