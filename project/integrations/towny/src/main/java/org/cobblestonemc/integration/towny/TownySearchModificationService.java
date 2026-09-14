/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.integration.towny;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.TownySettings;
import com.palmergames.bukkit.towny.event.executors.TownyActionEventExecutor;
import com.palmergames.bukkit.towny.exceptions.TownyException;
import com.palmergames.bukkit.towny.object.Nation;
import com.palmergames.bukkit.towny.object.Resident;
import com.palmergames.bukkit.towny.object.Town;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.cobblestonemc.paper.api.BreakChecker;
import org.cobblestonemc.paper.api.SearchModificationService;
import org.cobblestonemc.paper.api.Transition;

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
 * <p><b>Breakability</b> — {@code TownyActionEventExecutor.canDestroy} decides whether the player
 * may dig a block, so mining routes avoid protected land. It fires Towny's own event, so it must
 * run on the main thread; the result is delivered through the future, and answers are cached per
 * search by plot and material.
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
      return TownySettings.isConfigAllowingTownSpawn() && player.hasPermission(SPAWN_TOWN);
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
    // One cache per search: a search asks about thousands of blocks, and every miss is a hop to the
    // main thread and back — the search parks for the whole round trip each time. Towny's answer,
    // though, is a property of the town block and the material, not of the individual block: every
    // stone block in one plot answers the same. So the thousands of questions a search actually has
    // collapse to a handful of distinct ones.
    //
    // The plot size is read here, on the search-initiating thread, so the key can be computed
    // without touching Towny from a search worker.
    Map<PermissionKey, CompletableFuture<Boolean>> cache = new ConcurrentHashMap<>();
    int townBlockSize = TownySettings.getTownBlockSize();
    return (breaker, location, block) -> {
      // Towny's answer turns on the material, so the state has to be read here — but only once
      // per block, and the cached verdict then answers every other block like it for free.
      Material material = block.get().getMaterial();
      return cache.computeIfAbsent(
          PermissionKey.of(location, material, townBlockSize),
          key -> ask(breaker, location, material));
    };
  }

  /** Puts one breakability question to Towny on the main thread. */
  private CompletableFuture<Boolean> ask(Player breaker, Location location, Material material) {
    CompletableFuture<Boolean> future = new CompletableFuture<>();
    Bukkit.getScheduler()
        .runTask(
            plugin,
            () -> {
              if (!breaker.isOnline()) {
                future.complete(true); // gone; do not block mining
                return;
              }
              // canDestroy, not PlayerCacheUtil.getCachePermission: the latter answers from the
              // player's own movement cache, which is built for wherever the player is standing,
              // not for a house a thousand blocks away. canDestroy runs Towny's whole decision,
              // event and all.
              future.complete(TownyActionEventExecutor.canDestroy(breaker, location, material));
            });
    return future;
  }

  /**
   * What Towny's answer actually depends on: which town block the location falls in, in which
   * world, and the material being broken.
   *
   * <p>Deliberately coarser than the block position. Towny grants build rights per plot, so two
   * blocks of the same material in the same plot always get the same verdict; keying on the plot
   * turns a search's thousands of questions into a few. It errs towards asking more rather than
   * fewer — a plot is the finest granularity Towny itself distinguishes.
   */
  private record PermissionKey(String world, int townBlockX, int townBlockZ, Material material) {

    /**
     * The key for a location, where {@code townBlockSize} is Towny's configured plot width. It is
     * not always 16, and a key that assumed so would pool blocks from neighbouring plots under one
     * verdict.
     */
    static PermissionKey of(Location location, Material material, int townBlockSize) {
      return new PermissionKey(
          location.getWorld().getName(),
          Math.floorDiv(location.getBlockX(), townBlockSize),
          Math.floorDiv(location.getBlockZ(), townBlockSize),
          material);
    }
  }
}
