/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.sponge12.api;

import net.whimxiqal.odyssey.minecraft.api.WorldRegion;
import org.spongepowered.api.ResourceKey;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.world.server.ServerWorld;
import org.spongepowered.math.vector.Vector3i;

/**
 * A region that spans an entire world — every cell in it is contained. Used to navigate "to a
 * world": the search succeeds as soon as it arrives there (e.g. through a portal). Holds only the
 * world's key and re-resolves the {@link ServerWorld} on demand, so it never pins a possibly
 * unloaded world object.
 */
public final class WholeWorldRegion implements WorldRegion<ServerWorld, Vector3i> {

  private final String worldKey;

  /**
   * Creates a region for the world with the given namespaced key.
   *
   * @param worldKey the world's namespaced key (e.g. {@code minecraft:the_nether})
   */
  public WholeWorldRegion(String worldKey) {
    this.worldKey = worldKey;
  }

  /**
   * A region spanning the given world.
   *
   * @param world the world
   * @return the region
   */
  public static WholeWorldRegion of(ServerWorld world) {
    return new WholeWorldRegion(world.key().asString());
  }

  @Override
  public ServerWorld world() {
    return Sponge.server().worldManager().world(ResourceKey.resolve(worldKey)).orElse(null);
  }

  @Override
  public boolean contains(Vector3i vector) {
    return true;
  }

  @Override
  public Vector3i nearestBoundaryLocation(Vector3i vector) {
    return vector; // already inside; nothing to clamp
  }

  @Override
  public String toString() {
    return "WholeWorld[" + worldKey + "]";
  }
}
