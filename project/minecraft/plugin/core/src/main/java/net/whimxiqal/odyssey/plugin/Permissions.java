/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.plugin;

public enum Permissions {
  PERMISSION_NAVIGATE("odyssey.navigate"),
  PERMISSION_NAVIGATOR("odyssey.navigator"),
  PERMISSION_WAYPOINT("odyssey.waypoint"),
  PERMISSION_RELOAD("odyssey.admin.reload"),
  PERMISSION_PORTALS("odyssey.admin.portals"),
  PERMISSION_WAYPOINT_GLOBAL("odyssey.admin.waypoint.global");

  private final String perm;

  Permissions(String perm) {
    this.perm = perm;
  }

  public String value() {
    return perm;
  }
}
