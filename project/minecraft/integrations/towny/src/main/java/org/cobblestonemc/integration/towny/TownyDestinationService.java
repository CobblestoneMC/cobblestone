/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.integration.towny;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.object.Resident;
import com.palmergames.bukkit.towny.object.Town;
import com.palmergames.bukkit.towny.object.TownBlock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.cobblestonemc.paper.plugin.api.Destination;
import org.cobblestonemc.paper.plugin.api.DestinationService;
import org.cobblestonemc.plugin.api.DestinationEntry;
import org.cobblestonemc.plugin.api.DestinationTree;
import org.cobblestonemc.plugin.api.MinecraftDestination;
import org.joml.Vector3i;

/**
 * Surfaces Towny towns as navigation targets, keyed by the {@code /navigate} tree (Cobblestone
 * roots the branch at Towny's own name, so nothing here collides with another integration):
 *
 * <ul>
 *   <li>{@code towny → town → <town>} — anywhere in the town (all its claimed plots)
 *   <li>{@code towny → town → <town> → home} — the town's home block
 *   <li>{@code towny → town → <town> → outpost → <#>} — an outpost's block
 *   <li>{@code towny → town → <town> → type → <plottype>} — the nearest plot of a type (bank, shop,
 *       …)
 *   <li>{@code towny → town → <town> → plot → <name>} — a named plot
 *   <li>{@code towny → resident → …} — the same, for the player's own town (no need to type its
 *       name)
 * </ul>
 *
 * <p>Navigability is gated by Cobblestone's {@code cobblestone.navigate.towny.*} permission
 * (default-allow), not by whether the player may teleport there. Regions are read from Towny
 * lazily, on the search-initiating thread.
 */
final class TownyDestinationService implements DestinationService {

  @Override
  public DestinationTree<MinecraftDestination<World, Vector3i>> provide(Player player) {
    var towns = Destination.tree();
    for (Town town : TownyAPI.getInstance().getTowns()) {
      String name = town.getName();
      towns.entry(name, town(town));
    }

    var root = Destination.tree().subtree("town", towns);
    Town own = ownTown(player);
    if (own != null) {
      root.entry("resident", town(own));
    }
    return root.build();
  }

  /** A town is somewhere to go, and also holds its home, outposts and plots. */
  private static DestinationEntry.Builder<MinecraftDestination<World, Vector3i>> town(Town town) {
    return Destination.entry().destination(() -> wholeTown(town)).subtree(() -> townDetail(town));
  }

  private static DestinationTree<MinecraftDestination<World, Vector3i>> townDetail(Town town) {
    return Destination.tree()
        .strict()
        .destination("home", () -> Destination.at(town.getSpawnOrNull(), "home"))
        .subtree("outpost", () -> outposts(town))
        .subtree("type", () -> plots(town, TownBlock::getTypeName))
        .subtree("plot", () -> plots(town, TownBlock::getName))
        .build();
  }

  private static DestinationTree<MinecraftDestination<World, Vector3i>> outposts(Town town) {
    var tree = Destination.tree().strict();
    List<Location> spawns = town.getAllOutpostSpawns();
    for (int i = 0; i < spawns.size(); i++) {
      Location spawn = spawns.get(i);
      tree.destination(Integer.toString(i + 1), () -> Destination.at(spawn, "outpost"));
    }
    return tree.build();
  }

  /** A sub-tree keyed by a plot attribute (type or name); each leaf unions the matching plots. */
  private static DestinationTree<MinecraftDestination<World, Vector3i>> plots(
      Town town, Function<TownBlock, String> attribute) {
    var tree = Destination.tree().strict();
    Set<String> keys = new LinkedHashSet<>();
    for (TownBlock townBlock : town.getTownBlocks()) {
      String value = attribute.apply(townBlock);
      if (value != null && !value.isBlank()) {
        keys.add(value.toLowerCase(Locale.ROOT));
      }
    }
    for (String key : keys) {
      tree.destination(
          key,
          () ->
              Destination.regions(
                  () ->
                      TownyRegions.plots(
                          town, block -> key.equalsIgnoreCase(attribute.apply(block))),
                  key));
    }
    return tree.build();
  }

  private static MinecraftDestination<World, Vector3i> wholeTown(Town town) {
    return Destination.regions(() -> TownyRegions.plots(town, block -> true), town.getName());
  }

  private static Town ownTown(Player player) {
    Resident resident = TownyAPI.getInstance().getResident(player);
    return resident == null ? null : TownyAPI.getInstance().getResidentTownOrNull(resident);
  }
}
