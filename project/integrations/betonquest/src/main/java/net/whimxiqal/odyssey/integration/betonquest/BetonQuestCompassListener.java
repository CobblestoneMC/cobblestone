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

import net.whimxiqal.odyssey.paper.plugin.api.OdysseyPluginAPI;
import net.whimxiqal.odyssey.plugin.api.NavigatorSettings;
import org.betonquest.betonquest.api.BetonQuestApi;
import org.betonquest.betonquest.api.bukkit.event.QuestCompassTargetChangeEvent;
import org.betonquest.betonquest.api.identifier.CompassIdentifier;
import org.betonquest.betonquest.api.profile.Profile;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * The auto-navigation hook: BetonQuest fires {@link QuestCompassTargetChangeEvent} when a player
 * sets their quest compass to a target. If that compass opts into navigation ({@link
 * QuestNavPrefs}), we start — or, because a player's quest compass is single-target and the trip
 * carries a stable label, <em>replace</em> — a guided Odyssey trip to it.
 *
 * <p>The event carries only a location, so we recover the compass' tag by matching it against the
 * profile's active compasses; that gives a readable trip label and lets per-compass config apply.
 */
final class BetonQuestCompassListener implements Listener {

  // The trip label when the compass can't be identified — a player has one quest compass at a time.
  private static final String DEFAULT_LABEL = "compass";

  private final BetonQuestApi api;
  private final QuestNavPrefs prefs;

  BetonQuestCompassListener(BetonQuestApi api, QuestNavPrefs prefs) {
    this.api = api;
    this.prefs = prefs;
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onCompassTargetChange(QuestCompassTargetChangeEvent event) {
    Location target = event.getLocation();
    if (target == null || target.getWorld() == null) {
      return;
    }
    Profile profile = event.getProfile();
    CompassIdentifier identifier = CompassTargets.match(api, profile, target);
    String name = identifier == null ? null : identifier.get();
    if (!prefs.autoNavigate(name)) {
      return;
    }
    Player player = Bukkit.getPlayer(profile.getPlayerUUID());
    if (player == null) {
      return;
    }
    NavigatorSettings settings = prefs.settings(name);
    // A stable label so re-pointing the compass replaces the previous compass trip.
    String label = name == null ? DEFAULT_LABEL : name;
    OdysseyPluginAPI.tripService()
        .navigate(
            player,
            target,
            settings,
            label,
            reason -> {
              // No route (or the search failed): nothing to do; the player can retry the compass.
            });
  }
}
