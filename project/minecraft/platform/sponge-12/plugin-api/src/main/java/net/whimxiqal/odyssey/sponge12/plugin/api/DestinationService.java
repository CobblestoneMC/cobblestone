/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.sponge12.plugin.api;

import net.whimxiqal.odyssey.plugin.api.PlatformDestinationTree;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import org.spongepowered.api.world.server.ServerWorld;
import org.spongepowered.math.vector.Vector3i;

/** Supplies the destinations one integration offers a player. */
@FunctionalInterface
public interface DestinationService {

  /**
   * The root of this integration's destination tree for the given player.
   *
   * <p>The root's own key is <b>not</b> chosen here: Odyssey files the tree under the registering
   * plugin's id, so two plugins offering a {@code warp} level stay distinguishable. Build the
   * levels below that — {@code DestinationTree.builder().subtree("warp", …)} — not a level named
   * after the plugin itself.
   *
   * @param player the player requesting navigation
   * @return the (lazily-evaluated) tree, or {@code null} if this integration has nothing to offer
   *     this player right now
   */
  @Nullable
  PlatformDestinationTree<ServerWorld, Vector3i> provide(ServerPlayer player);
}
