/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.integration.essentials;

import com.earth2me.essentials.spawn.IEssentialsSpawn;
import java.util.List;
import net.ess3.api.IEssentials;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * A thin wrapper over the EssentialsX API — the only place this integration touches Essentials.
 * Holds the (interface) plugin handles resolved once at enable time; the spawn module is optional.
 */
final class Essentials {

  static final String HOME_PERMISSION = "essentials.home";
  static final String SPAWN_PERMISSION = "essentials.spawn";

  private final IEssentials essentials;
  private final IEssentialsSpawn spawn; // nullable: the EssentialsSpawn module may not be installed

  Essentials(IEssentials essentials, IEssentialsSpawn spawn) {
    this.essentials = essentials;
    this.spawn = spawn;
  }

  /** The player's home names. */
  List<String> homes(Player player) {
    return essentials.getUser(player).getHomes();
  }

  /**
   * The location of one of the player's homes, or {@code null} if it is gone / its world unloaded.
   */
  Location home(Player player, String name) {
    Location location = essentials.getUser(player).getHome(name);
    return location == null || location.getWorld() == null ? null : location;
  }

  /** Whether the spawn module is present. */
  boolean hasSpawn() {
    return spawn != null;
  }

  /**
   * The spawn location for the player's group, or {@code null} if unavailable / its world unloaded.
   */
  Location spawn(Player player) {
    if (spawn == null) {
      return null;
    }
    Location location = spawn.getSpawn(essentials.getUser(player).getGroup());
    return location == null || location.getWorld() == null ? null : location;
  }
}
