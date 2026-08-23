/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.example.warps;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.joml.Vector3i;

/**
 * Transient per-player box selections made with the wooden-shovel wand (left-click = corner 1,
 * right-click = corner 2). Not persisted. All access is on the main thread (interaction events and
 * commands), so a plain map suffices. Cleared on portal creation and on logout.
 */
final class Selections {

  /** A player's in-progress box selection; either corner may be unset. */
  static final class Selection {
    String world;
    Vector3i corner1;
    Vector3i corner2;

    boolean hasCorner1() {
      return corner1 != null;
    }

    boolean hasCorner2() {
      return corner2 != null;
    }

    boolean complete() {
      return hasCorner1() && hasCorner2();
    }
  }

  private final Map<UUID, Selection> byPlayer = new HashMap<>();

  private Selection forPlayer(UUID id, String world) {
    Selection selection = byPlayer.computeIfAbsent(id, key -> new Selection());
    if (selection.world != null && !selection.world.equals(world)) {
      // Corners must share a world; clicking in a new one starts the selection over there.
      selection.corner1 = null;
      selection.corner2 = null;
    }
    selection.world = world;
    return selection;
  }

  void setCorner1(UUID id, String world, Vector3i block) {
    forPlayer(id, world).corner1 = block;
  }

  void setCorner2(UUID id, String world, Vector3i block) {
    forPlayer(id, world).corner2 = block;
  }

  Selection get(UUID id) {
    return byPlayer.get(id);
  }

  void clear(UUID id) {
    byPlayer.remove(id);
  }
}
