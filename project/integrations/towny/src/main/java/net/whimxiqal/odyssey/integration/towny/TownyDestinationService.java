/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.integration.towny;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.object.Resident;
import com.palmergames.bukkit.towny.object.Town;
import com.palmergames.bukkit.towny.object.TownBlock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.whimxiqal.odyssey.paper.plugin.api.Destination;
import net.whimxiqal.odyssey.paper.plugin.api.DestinationService;
import net.whimxiqal.odyssey.paper.plugin.api.DestinationTree;
import net.whimxiqal.odyssey.plugin.api.MinecraftDestination;
import net.whimxiqal.odyssey.plugin.api.PlatformDestinationTree;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.joml.Vector3i;

/**
 * Surfaces Towny towns as navigation targets, keyed by the {@code /navigate} tree (Odyssey roots
 * the branch at this plugin's own name, so nothing here collides with another integration):
 *
 * <ul>
 *   <li>{@code odysseytowny → town → <town>} — anywhere in the town (all its claimed plots)
 *   <li>{@code odysseytowny → town → <town> → home} — the town's home block
 *   <li>{@code odysseytowny → town → <town> → outpost → <#>} — an outpost's block
 *   <li>{@code odysseytowny → town → <town> → type → <plottype>} — the nearest plot of a type
 *       (bank, shop, …)
 *   <li>{@code odysseytowny → town → <town> → plot → <name>} — a named plot
 *   <li>{@code odysseytowny → resident → …} — the same, for the player's own town (no need to type
 *       its name)
 * </ul>
 *
 * <p>Navigability is gated by Odyssey's {@code odyssey.navigate.odysseytowny.*} permission
 * (default-allow), not by whether the player may teleport there. Regions are read from Towny
 * lazily, on the search-initiating thread.
 */
final class TownyDestinationService implements DestinationService {

  @Override
  public PlatformDestinationTree<World, Vector3i> provide(Player player) {
    DestinationTree towns = DestinationTree.builder().strict();
    for (Town town : TownyAPI.getInstance().getTowns()) {
      String name = town.getName();
      // The whole town is a leaf; its home/outpost/type/plot are a sub-tree — same key, both roles.
      towns.leaf(name, () -> wholeTown(town));
      towns.subtree(name, () -> townDetail(town));
    }

    DestinationTree root = DestinationTree.builder().subtree("town", towns);
    Town own = ownTown(player);
    if (own != null) {
      root.subtree("resident", () -> townDetail(own));
      root.leaf("resident", () -> wholeTown(own));
    }
    return root.build();
  }

  private static PlatformDestinationTree<World, Vector3i> townDetail(Town town) {
    return DestinationTree.builder()
        .strict()
        .leaf("home", () -> Destination.at(town.getSpawnOrNull(), "home"))
        .subtree("outpost", () -> outposts(town))
        .subtree("type", () -> plots(town, TownBlock::getTypeName))
        .subtree("plot", () -> plots(town, TownBlock::getName))
        .build();
  }

  private static PlatformDestinationTree<World, Vector3i> outposts(Town town) {
    DestinationTree tree = DestinationTree.builder().strict();
    List<Location> spawns = town.getAllOutpostSpawns();
    for (int i = 0; i < spawns.size(); i++) {
      Location spawn = spawns.get(i);
      tree.leaf(Integer.toString(i + 1), () -> Destination.at(spawn, "outpost"));
    }
    return tree.build();
  }

  /** A sub-tree keyed by a plot attribute (type or name); each leaf unions the matching plots. */
  private static PlatformDestinationTree<World, Vector3i> plots(
      Town town, java.util.function.Function<TownBlock, String> attribute) {
    DestinationTree tree = DestinationTree.builder().strict();
    Set<String> keys = new LinkedHashSet<>();
    for (TownBlock townBlock : town.getTownBlocks()) {
      String value = attribute.apply(townBlock);
      if (value != null && !value.isBlank()) {
        keys.add(value.toLowerCase(Locale.ROOT));
      }
    }
    for (String key : keys) {
      tree.leaf(
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
