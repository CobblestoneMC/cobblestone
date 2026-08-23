/*
 * CobblestoneBetonQuest — a BetonQuest integration for the Cobblestone navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. Because it links against BetonQuest (GPL-3.0), this
 * module is distributed under the GPL rather than Cobblestone's MIT license. It is distributed WITHOUT
 * ANY WARRANTY. See the GNU General Public License (the LICENSE file in this module) for details.
 */
package org.cobblestonemc.integration.betonquest;

import java.util.Map;
import org.betonquest.betonquest.api.BetonQuestApi;
import org.betonquest.betonquest.api.QuestException;
import org.betonquest.betonquest.api.compass.QuestCompass;
import org.betonquest.betonquest.api.identifier.CompassIdentifier;
import org.betonquest.betonquest.api.profile.Profile;
import org.bukkit.Location;

/**
 * Resolves BetonQuest quest-compass locations. A compass' location is an {@code Argument<Location>}
 * that must be resolved against a profile (it may contain placeholders); resolution can fail, so we
 * return {@code null} rather than propagate. Must be called on the main thread.
 */
final class CompassTargets {

  private CompassTargets() {}

  /** The resolved location of {@code compass} for {@code profile}, or {@code null}. */
  static Location locationOf(QuestCompass compass, Profile profile) {
    try {
      Location location = compass.location().getValue(profile);
      return location != null && location.getWorld() != null ? location : null;
    } catch (QuestException e) {
      return null; // a compass whose location can't be resolved right now is simply not offered
    }
  }

  /**
   * Finds which of the profile's active compasses points at {@code target} (by block cell) — used
   * to recover a compass' tag from the location-only compass event. Returns {@code null} if none
   * match.
   */
  static CompassIdentifier match(BetonQuestApi api, Profile profile, Location target) {
    for (Map.Entry<CompassIdentifier, QuestCompass> entry :
        api.compasses().forProfile(profile).entrySet()) {
      Location location = locationOf(entry.getValue(), profile);
      if (location != null && sameCell(location, target)) {
        return entry.getKey();
      }
    }
    return null;
  }

  private static boolean sameCell(Location a, Location b) {
    return a.getWorld().equals(b.getWorld())
        && a.getBlockX() == b.getBlockX()
        && a.getBlockY() == b.getBlockY()
        && a.getBlockZ() == b.getBlockZ();
  }
}
