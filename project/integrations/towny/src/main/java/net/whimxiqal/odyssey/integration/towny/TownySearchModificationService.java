/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.integration.towny;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.TownySettings;
import com.palmergames.bukkit.towny.exceptions.TownyException;
import com.palmergames.bukkit.towny.object.Nation;
import com.palmergames.bukkit.towny.object.Resident;
import com.palmergames.bukkit.towny.object.Town;
import com.palmergames.bukkit.towny.object.TownyPermission.ActionType;
import com.palmergames.bukkit.towny.utils.PlayerCacheUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.whimxiqal.odyssey.paper.api.BreakChecker;
import net.whimxiqal.odyssey.paper.api.SearchModificationService;
import net.whimxiqal.odyssey.paper.api.Transition;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * The Towny search hook: teleport shortcuts as transitions, and Towny's build protection as a
 * breakability check.
 *
 * <p><b>Transitions</b> — {@code /town spawn}, {@code /nation spawn}, and {@code /town outpost},
 * each offered only when the player may actually run it (Towny permission, plus
 * public/own/nation/ally, outlaw and enemy checks). The gating here mirrors Towny's common rules;
 * unusual states (wars, jailing) are left to Towny to reject. Read on the search-initiating (main)
 * thread.
 *
 * <p><b>Breakability</b> — {@code PlayerCacheUtil.getCachePermission(…, DESTROY)} decides whether
 * the player may dig a block, so mining routes avoid protected land. That call touches Towny's
 * caches, so it is hopped to the main thread and its result delivered through the future.
 */
final class TownySearchModificationService implements SearchModificationService {

  private static final double TELEPORT_COST_SECONDS = 3.0;

  private static final String SPAWN_TOWN = "towny.town.spawn.town";
  private static final String SPAWN_NATION = "towny.town.spawn.nation";
  private static final String SPAWN_ALLY = "towny.town.spawn.ally";
  private static final String SPAWN_PUBLIC = "towny.town.spawn.public";
  private static final String SPAWN_OUTPOST = "towny.town.spawn.outpost";
  private static final String NATION_SPAWN_NATION = "towny.nation.spawn.nation";
  private static final String NATION_SPAWN_ALLY = "towny.nation.spawn.ally";
  private static final String NATION_SPAWN_PUBLIC = "towny.nation.spawn.public";

  private final Plugin plugin;

  TownySearchModificationService(Plugin plugin) {
    this.plugin = plugin;
  }

  @Override
  public CompletableFuture<List<Transition>> computeTransitions(Player player) {
    List<Transition> result = new ArrayList<>();
    TownyAPI api = TownyAPI.getInstance();
    Resident resident = api.getResident(player);
    Town ownTown = resident == null ? null : api.getResidentTownOrNull(resident);
    Nation ownNation = ownTown == null ? null : api.getTownNationOrNull(ownTown);

    for (Town town : api.getTowns()) {
      Location spawn = town.getSpawnOrNull();
      if (spawn != null && canSpawnToTown(player, resident, ownTown, ownNation, town)) {
        result.add(command(player, spawn, "/town spawn " + town.getName()));
      }
    }
    if (ownTown != null && player.hasPermission(SPAWN_OUTPOST)) {
      List<Location> outposts = ownTown.getAllOutpostSpawns();
      for (int i = 0; i < outposts.size(); i++) {
        result.add(command(player, outposts.get(i), "/town outpost " + (i + 1)));
      }
    }
    for (Nation nation : api.getNations()) {
      Location spawn = nationSpawn(nation);
      if (spawn != null && canSpawnToNation(player, ownNation, nation)) {
        result.add(command(player, spawn, "/nation spawn " + nation.getName()));
      }
    }
    return CompletableFuture.completedFuture(result);
  }

  private boolean canSpawnToTown(
      Player player, Resident resident, Town ownTown, Nation ownNation, Town town) {
    if (town.equals(ownTown)) {
      return player.hasPermission(SPAWN_TOWN);
    }
    if (resident != null && town.hasOutlaw(resident)) {
      return false;
    }
    Nation townNation = TownyAPI.getInstance().getTownNationOrNull(town);
    if (ownNation != null && townNation != null) {
      if (ownNation.hasEnemy(townNation)) {
        return false;
      }
      if (ownNation.equals(townNation)) {
        return player.hasPermission(SPAWN_NATION);
      }
      if (ownNation.isAlliedWith(townNation)) {
        return player.hasPermission(SPAWN_ALLY);
      }
    }
    return town.isPublic()
        && TownySettings.isConfigAllowingPublicTownSpawnTravel()
        && player.hasPermission(SPAWN_PUBLIC);
  }

  private boolean canSpawnToNation(Player player, Nation ownNation, Nation nation) {
    if (nation.equals(ownNation)) {
      return player.hasPermission(NATION_SPAWN_NATION);
    }
    if (ownNation != null && ownNation.isAlliedWith(nation)) {
      return player.hasPermission(NATION_SPAWN_ALLY);
    }
    return nation.isPublic() && player.hasPermission(NATION_SPAWN_PUBLIC);
  }

  private static Location nationSpawn(Nation nation) {
    try {
      return nation.getSpawn();
    } catch (TownyException e) {
      return null;
    }
  }

  private static Transition command(Player player, Location destination, String command) {
    return Transition.command(player, destination, TELEPORT_COST_SECONDS, command);
  }

  @Override
  public BreakChecker computeBreakChecker(Player player) {
    return (breaker, location, block) -> {
      CompletableFuture<Boolean> future = new CompletableFuture<>();
      // getCachePermission touches Towny's caches / Bukkit; evaluate it on the main thread.
      Bukkit.getScheduler()
          .runTask(
              plugin,
              () -> {
                if (!breaker.isOnline()) {
                  future.complete(true); // gone; do not block mining
                  return;
                }
                future.complete(
                    PlayerCacheUtil.getCachePermission(
                        breaker, location, block.getMaterial(), ActionType.DESTROY));
              });
      return future;
    };
  }
}
