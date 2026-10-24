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
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.cobblestonemc.paper.plugin.api.Destination;
import org.cobblestonemc.paper.plugin.api.DestinationService;
import org.cobblestonemc.plugin.api.DestinationEntry;
import org.cobblestonemc.plugin.api.DestinationTree;
import org.cobblestonemc.plugin.api.Display;
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
 * <p>How each entry looks in the navigation menu comes from this plugin's config ({@link
 * TownyIcons}).
 *
 * <p>Navigability is gated by Cobblestone's {@code cobblestone.navigate.towny.*} permission
 * (default-allow), not by whether the player may teleport there. Regions are read from Towny
 * lazily, on the search-initiating thread.
 */
final class TownyDestinationService implements DestinationService {

  private final TownyIcons icons;

  TownyDestinationService(TownyIcons icons) {
    this.icons = icons;
  }

  @Override
  public DestinationTree<MinecraftDestination<World, Vector3i>> provide(Player player) {
    var towns = Destination.tree();
    for (Town town : TownyAPI.getInstance().getTowns()) {
      towns.entry(town.getName(), townEntry(town));
    }

    var root = Destination.tree().entry("town", shown(icons.towns()).subtree(towns));
    Town own = ownTown(player);
    if (own != null) {
      root.entry("resident", townEntry(own).label(null));
    }
    return root.build();
  }

  @Override
  public Display display(Player player) {
    return icons.root();
  }

  /** A town: somewhere to go, which also holds its home, outposts and plots. */
  private DestinationEntry.Builder<MinecraftDestination<World, Vector3i>> townEntry(Town town) {
    return shown(icons.town(town))
        .label(label(town))
        .destination(() -> wholeTown(town))
        .subtree(() -> townDetail(town));
  }

  private DestinationTree<MinecraftDestination<World, Vector3i>> townDetail(Town town) {
    return Destination.tree()
        .strict()
        .entry(
            "home",
            shown(icons.home()).destination(() -> Destination.at(town.getSpawnOrNull(), "home")))
        .entry("outpost", shown(icons.outposts()).subtree(() -> outposts(town)))
        .entry(
            "type",
            shown(icons.plotTypes())
                .subtree(() -> plots(town, TownBlock::getTypeName, icons.plotType())))
        .entry(
            "plot",
            shown(icons.plots()).subtree(() -> plots(town, TownBlock::getName, icons.plot())))
        .build();
  }

  private DestinationTree<MinecraftDestination<World, Vector3i>> outposts(Town town) {
    var tree = Destination.tree().strict();
    List<Location> spawns = town.getAllOutpostSpawns();
    for (int i = 0; i < spawns.size(); i++) {
      Location spawn = spawns.get(i);
      tree.entry(
          Integer.toString(i + 1),
          shown(icons.outpost()).destination(() -> Destination.at(spawn, "outpost")));
    }
    return tree.build();
  }

  /** A sub-tree keyed by a plot attribute (type or name); each leaf unions the matching plots. */
  private static DestinationTree<MinecraftDestination<World, Vector3i>> plots(
      Town town, Function<TownBlock, String> attribute, Display display) {
    var tree = Destination.tree().strict();
    Set<String> keys = new LinkedHashSet<>();
    for (TownBlock townBlock : town.getTownBlocks()) {
      String value = attribute.apply(townBlock);
      if (value != null && !value.isBlank()) {
        keys.add(value.toLowerCase(Locale.ROOT));
      }
    }
    for (String key : keys) {
      tree.entry(
          key,
          shown(display)
              .destination(
                  () ->
                      Destination.regions(
                          () ->
                              TownyRegions.plots(
                                  town, block -> key.equalsIgnoreCase(attribute.apply(block))),
                          key)));
    }
    return tree.build();
  }

  private static MinecraftDestination<World, Vector3i> wholeTown(Town town) {
    return Destination.regions(() -> TownyRegions.plots(town, block -> true), town.getName());
  }

  /** An entry shown with the given display, to give a destination or a subtree. */
  private static DestinationEntry.Builder<MinecraftDestination<World, Vector3i>> shown(
      Display display) {
    return Destination.entry().display(display);
  }

  /** Towny writes spaces in a town's name as underscores. */
  private static Component label(Town town) {
    return Component.text(town.getName().replace('_', ' '));
  }

  private static Town ownTown(Player player) {
    Resident resident = TownyAPI.getInstance().getResident(player);
    return resident == null ? null : TownyAPI.getInstance().getResidentTownOrNull(resident);
  }
}
