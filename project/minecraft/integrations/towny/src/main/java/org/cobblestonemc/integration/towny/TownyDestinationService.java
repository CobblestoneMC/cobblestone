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
import org.cobblestonemc.paper.plugin.api.DestinationTree;
import org.cobblestonemc.plugin.api.DestinationChild;
import org.cobblestonemc.plugin.api.Display;
import org.cobblestonemc.plugin.api.MinecraftDestination;
import org.cobblestonemc.plugin.api.PlatformDestinationTree;
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
  public PlatformDestinationTree<World, Vector3i> provide(Player player) {
    DestinationTree towns = DestinationTree.builder();
    for (Town town : TownyAPI.getInstance().getTowns()) {
      towns.child(town.getName(), townEntry(town));
    }

    DestinationTree root =
        DestinationTree.builder().child("town", shown(icons.towns()).children(towns::build));
    Town own = ownTown(player);
    if (own != null) {
      root.child("resident", townEntry(own).label(null));
    }
    return root.build();
  }

  @Override
  public Display display(Player player) {
    return icons.root();
  }

  /** A town: somewhere to go, which also holds its home, outposts and plots. */
  private DestinationChild.Builder<MinecraftDestination<World, Vector3i>> townEntry(Town town) {
    return shown(icons.town(town))
        .label(label(town))
        .destination(() -> wholeTown(town))
        .children(() -> townDetail(town));
  }

  private PlatformDestinationTree<World, Vector3i> townDetail(Town town) {
    return DestinationTree.builder()
        .strict()
        .child(
            "home",
            shown(icons.home()).destination(() -> Destination.at(town.getSpawnOrNull(), "home")))
        .child("outpost", shown(icons.outposts()).children(() -> outposts(town)))
        .child(
            "type",
            shown(icons.plotTypes())
                .children(() -> plots(town, TownBlock::getTypeName, icons.plotType())))
        .child(
            "plot",
            shown(icons.plots()).children(() -> plots(town, TownBlock::getName, icons.plot())))
        .build();
  }

  private PlatformDestinationTree<World, Vector3i> outposts(Town town) {
    DestinationTree tree = DestinationTree.builder().strict();
    List<Location> spawns = town.getAllOutpostSpawns();
    for (int i = 0; i < spawns.size(); i++) {
      Location spawn = spawns.get(i);
      tree.child(
          Integer.toString(i + 1),
          shown(icons.outpost()).destination(() -> Destination.at(spawn, "outpost")));
    }
    return tree.build();
  }

  /** A sub-tree keyed by a plot attribute (type or name); each leaf unions the matching plots. */
  private static PlatformDestinationTree<World, Vector3i> plots(
      Town town, Function<TownBlock, String> attribute, Display display) {
    DestinationTree tree = DestinationTree.builder().strict();
    Set<String> keys = new LinkedHashSet<>();
    for (TownBlock townBlock : town.getTownBlocks()) {
      String value = attribute.apply(townBlock);
      if (value != null && !value.isBlank()) {
        keys.add(value.toLowerCase(Locale.ROOT));
      }
    }
    for (String key : keys) {
      tree.child(
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

  /** An entry shown with the given display, to give a destination or a level. */
  private static DestinationChild.Builder<MinecraftDestination<World, Vector3i>> shown(
      Display display) {
    return DestinationTree.child().display(display);
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
