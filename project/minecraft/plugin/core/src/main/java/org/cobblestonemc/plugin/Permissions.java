/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin;

import org.cobblestonemc.plugin.destination.NavigationPermissions;

/**
 * The permission nodes Cobblestone's own commands check, in one place so the two platform command
 * trees cannot drift apart. Each platform declares these to its permission system with the defaults
 * documented here (Paper in {@code paper-plugin.yml}, Sponge via {@code PermissionDescription}).
 *
 * <p>The per-destination navigation gate ({@code cobblestone.navigate.<address>}) is <i>not</i>
 * here — it is generated per address and is default-allow; see {@link NavigationPermissions}.
 */
public enum Permissions {

  /** Use of {@code /navigate} and the trip commands that manage its results. Default allow. */
  NAVIGATE("cobblestone.navigate"),

  /**
   * Prefix of the per-navigator node, {@code cobblestone.navigator.<id>} — checked only for a
   * non-default navigator. Default allow.
   */
  NAVIGATOR("cobblestone.navigator"),

  /** Use of {@code /cobblestone location}, for the player's own locations. Default allow. */
  LOCATION("cobblestone.location"),

  /** Reloading the configuration. Default op. */
  RELOAD("cobblestone.admin.reload"),

  /** Clearing the discovered-portal store. Default op. */
  PORTALS("cobblestone.admin.portals"),

  /** Creating and deleting server-wide locations ({@code -global}). Default op. */
  LOCATION_GLOBAL("cobblestone.admin.location.global");

  private final String node;

  Permissions(String node) {
    this.node = node;
  }

  /**
   * The permission node as a string.
   *
   * @return the node
   */
  public String value() {
    return node;
  }
}
