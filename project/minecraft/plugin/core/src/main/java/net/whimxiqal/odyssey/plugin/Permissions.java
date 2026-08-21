/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.plugin;

/**
 * The permission nodes Odyssey's own commands check, in one place so the two platform command trees
 * cannot drift apart. Each platform declares these to its permission system with the defaults
 * documented here (Paper in {@code paper-plugin.yml}, Sponge via {@code PermissionDescription}).
 *
 * <p>The per-destination navigation gate ({@code odyssey.navigate.<address>}) is <i>not</i> here —
 * it is generated per address and is default-allow; see {@link
 * net.whimxiqal.odyssey.plugin.destination.NavigationPermissions}.
 */
public enum Permissions {

  /** Use of {@code /navigate} and the trip commands that manage its results. Default allow. */
  NAVIGATE("odyssey.navigate"),

  /**
   * Prefix of the per-navigator node, {@code odyssey.navigator.<id>} — checked only for a
   * non-default navigator. Default allow.
   */
  NAVIGATOR("odyssey.navigator"),

  /** Use of {@code /odyssey waypoint}, for the player's own waypoints. Default allow. */
  WAYPOINT("odyssey.waypoint"),

  /** Reloading the configuration. Default op. */
  RELOAD("odyssey.admin.reload"),

  /** Clearing the discovered-portal store. Default op. */
  PORTALS("odyssey.admin.portals"),

  /** Creating and deleting server-wide waypoints ({@code -global}). Default op. */
  WAYPOINT_GLOBAL("odyssey.admin.waypoint.global");

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
