/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.example.warps;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepPayload;
import net.whimxiqal.odyssey.paper.api.BoxWorldRegion;
import net.whimxiqal.odyssey.paper.api.PaperSearchModificationService;
import net.whimxiqal.odyssey.paper.api.PaperTransition;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Surfaces both travel modalities to Odyssey's searches:
 *
 * <ul>
 *   <li><b>Warps</b> → a {@code COMMAND} transition whose origin is the whole world the player is
 *       currently in. Because the origin already contains the search start, Odyssey can take it
 *       right away and prompt the player to type {@code /warp <name>}.
 *   <li><b>Portals</b> → a {@code PORTAL} transition whose origin is the entrance box; the player
 *       walks into it and is auto-teleported (see {@link WarpListeners}). Its destination is
 *       resolved live from the referenced {@link Destination}, so editing that destination moves
 *       the edge.
 * </ul>
 *
 * <p>Registered as a Bukkit service; Odyssey discovers it and calls {@link
 * #computeTransitions(Player)} per search. (A modifier can also constrain mining or passage; this
 * example only adds routes and leaves those at their permissive defaults.)
 */
final class WarpSearchModificationService implements PaperSearchModificationService {

  private final WarpStore store;

  WarpSearchModificationService(WarpStore store) {
    this.store = store;
  }

  @Override
  public CompletableFuture<List<PaperTransition>> computeTransitions(Player player) {
    List<PaperTransition> result = new ArrayList<>();
    addWarps(player, result);
    addPortals(result);
    return CompletableFuture.completedFuture(result);
  }

  private void addWarps(Player player, List<PaperTransition> result) {
    for (Warp warp : store.warps()) {
      World world = Worlds.byKey(warp.world());
      if (world == null) {
        continue; // warp's world unloaded; skip until it is back
      }
      // /warp works from anywhere: a wormhole from the player's whole current world, so the search
      // can prompt "/warp <name>" immediately.
      result.add(
          PaperTransition.command(
              player, warp.toLocation(world), warp.cost(), "/warp " + warp.name()));
    }
  }

  private void addPortals(List<PaperTransition> result) {
    for (Portal portal : store.portals()) {
      Optional<Destination> destination = store.getDestination(portal.destination());
      if (destination.isEmpty()) {
        continue; // dangling reference (destination removed); the portal is inert until it returns
      }
      World portalWorld = Worlds.byKey(portal.world());
      World destWorld = Worlds.byKey(destination.get().world());
      if (portalWorld == null || destWorld == null) {
        continue; // a world unloaded; skip until it is back
      }
      BoxWorldRegion origin =
          BoxWorldRegion.of(
              new Location(portalWorld, portal.minX(), portal.minY(), portal.minZ()),
              new Location(portalWorld, portal.maxX(), portal.maxY(), portal.maxZ()));
      Location dest = destination.get().toLocation(destWorld);
      result.add(PaperTransition.of(origin, dest, portal.cost(), MinecraftStepPayload.portal()));
    }
  }
}
