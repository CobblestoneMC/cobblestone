/*
 * OdysseyBetonQuest — a BetonQuest integration for the Odyssey navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. Because it links against BetonQuest (GPL-3.0), this
 * module is distributed under the GPL rather than Odyssey's MIT license. It is distributed WITHOUT
 * ANY WARRANTY. See the GNU General Public License (the LICENSE file in this module) for details.
 */
package net.whimxiqal.odyssey.integration.betonquest;

import java.util.Locale;
import java.util.Map;
import net.whimxiqal.odyssey.paper.plugin.api.Destination;
import net.whimxiqal.odyssey.paper.plugin.api.DestinationService;
import net.whimxiqal.odyssey.paper.plugin.api.DestinationTree;
import net.whimxiqal.odyssey.plugin.api.PlatformDestinationTree;
import org.betonquest.betonquest.api.BetonQuestApi;
import org.betonquest.betonquest.api.compass.QuestCompass;
import org.betonquest.betonquest.api.identifier.CompassIdentifier;
import org.betonquest.betonquest.api.profile.OnlineProfile;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.joml.Vector3i;

/**
 * Surfaces the player's active quest compasses as navigation targets: {@code odysseybetonquest →
 * compass → <name>}, one leaf per compass whose location resolves. Odyssey roots the branch at this
 * plugin's own name, which keeps these from colliding with other integrations' trees; its resolver
 * still lets a player type just the compass name when it's unambiguous. Navigating is gated by
 * Odyssey's {@code odyssey.navigate.odysseybetonquest.compass.*} permission (default-allow).
 * Targets are snapshotted when the tree is built (on the main thread, as compass-location
 * resolution requires).
 */
final class BetonQuestDestinationService implements DestinationService {

  static final String COMPASS_KEY = "compass";

  private final BetonQuestApi api;

  BetonQuestDestinationService(BetonQuestApi api) {
    this.api = api;
  }

  @Override
  public PlatformDestinationTree<World, Vector3i> provide(Player player) {
    OnlineProfile profile = api.profiles().getProfile(player);
    if (profile == null) {
      return null;
    }
    Map<CompassIdentifier, QuestCompass> compasses = api.compasses().forProfile(profile);
    DestinationTree compass = DestinationTree.builder();
    boolean any = false;
    for (Map.Entry<CompassIdentifier, QuestCompass> entry : compasses.entrySet()) {
      Location target = CompassTargets.locationOf(entry.getValue(), profile);
      if (target == null) {
        continue; // location couldn't be resolved — nothing to walk to
      }
      String name = entry.getKey().get();
      compass.leaf(slug(name), Destination.at(target, name));
      any = true;
    }
    return any ? DestinationTree.builder().subtree(COMPASS_KEY, compass).build() : null;
  }

  /**
   * A single command token from a compass name: lowercase, non-alphanumeric runs become one dash.
   */
  private static String slug(String name) {
    String slug =
        name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    return slug.isEmpty() ? "compass" : slug;
  }
}
