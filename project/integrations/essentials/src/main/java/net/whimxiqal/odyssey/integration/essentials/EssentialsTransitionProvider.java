/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.integration.essentials;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepPayload;
import net.whimxiqal.odyssey.paper.api.OdysseySearchModifier;
import net.whimxiqal.odyssey.paper.api.PaperTransition;
import net.whimxiqal.odyssey.paper.api.WholeWorldRegion;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Surfaces the player's {@code /home} and {@code /spawn} teleports to searches as {@code COMMAND}
 * transitions: reach anywhere in the current world, run the command, arrive at the destination. So a
 * route may "use" a teleport as a wormhole in Tier 1, and the navigator prompts the player to run it.
 *
 * <p>A teleport is only offered if the player actually has permission to run it — checked here, per
 * search, so revoking {@code essentials.home} immediately removes it as a routing option.
 */
final class EssentialsTransitionProvider implements OdysseySearchModifier {

  // Teleport commands are ~instant; a small non-zero cost keeps the search from over-preferring them.
  private static final double TELEPORT_COST_SECONDS = 3.0;

  private final Essentials essentials;

  EssentialsTransitionProvider(Essentials essentials) {
    this.essentials = essentials;
  }

  @Override
  public CompletableFuture<List<PaperTransition>> computeTransitions(Player player) {
    List<PaperTransition> result = new ArrayList<>();
    // /home and /spawn work from anywhere, so the origin is the player's whole current world: the
    // search start is always inside it, which lets Odyssey prompt the command immediately.
    WholeWorldRegion origin = new WholeWorldRegion(player.getWorld().getKey().asString());

    if (player.hasPermission(Essentials.HOME_PERMISSION)) {
      for (String home : essentials.homes(player)) {
        addTeleport(result, origin, essentials.home(player, home), "/home " + home);
      }
    }
    if (essentials.hasSpawn() && player.hasPermission(Essentials.SPAWN_PERMISSION)) {
      addTeleport(result, origin, essentials.spawn(player), "/spawn");
    }
    return CompletableFuture.completedFuture(result);
  }

  private static void addTeleport(
      List<PaperTransition> result, WholeWorldRegion origin, Location destination, String command) {
    if (destination == null) {
      return; // gone, or its world is unloaded
    }
    result.add(PaperTransition.of(
        origin, destination, TELEPORT_COST_SECONDS, MinecraftStepPayload.command(command)));
  }
}
