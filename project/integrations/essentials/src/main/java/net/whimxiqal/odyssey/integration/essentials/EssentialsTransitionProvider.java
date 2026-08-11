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
import net.whimxiqal.odyssey.paper.api.PaperOdysseySearchModifier;
import net.whimxiqal.odyssey.paper.api.PaperTransition;
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
final class EssentialsTransitionProvider implements PaperOdysseySearchModifier {

  // Teleport commands are ~instant; a small non-zero cost keeps the search from over-preferring them.
  private static final double TELEPORT_COST_SECONDS = 3.0;

  private final Essentials essentials;

  EssentialsTransitionProvider(Essentials essentials) {
    this.essentials = essentials;
  }

  @Override
  public CompletableFuture<List<PaperTransition>> computeTransitions(Player player) {
    List<PaperTransition> result = new ArrayList<>();
    if (player.hasPermission(Essentials.HOME_PERMISSION)) {
      for (String home : essentials.homes(player)) {
        addTeleport(result, player, essentials.home(player, home), "/home " + home);
      }
    }
    if (essentials.hasSpawn() && player.hasPermission(Essentials.SPAWN_PERMISSION)) {
      addTeleport(result, player, essentials.spawn(player), "/spawn");
    }
    return CompletableFuture.completedFuture(result);
  }

  private static void addTeleport(
      List<PaperTransition> result, Player player, Location destination, String command) {
    if (destination == null) {
      return; // gone, or its world is unloaded
    }
    // /home and /spawn work from anywhere: a wormhole from the player's whole current world, so the
    // search can prompt the command immediately.
    result.add(PaperTransition.command(player, destination, TELEPORT_COST_SECONDS, command));
  }
}
