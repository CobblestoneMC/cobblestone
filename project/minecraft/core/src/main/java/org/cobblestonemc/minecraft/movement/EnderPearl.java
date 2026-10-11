/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.movement;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.cobblestonemc.Cell;
import org.cobblestonemc.FutureOr;
import org.cobblestonemc.Movement;
import org.cobblestonemc.api.TraversalState;
import org.cobblestonemc.minecraft.MinecraftBlock;
import org.cobblestonemc.minecraft.MinecraftKeys;
import org.cobblestonemc.minecraft.MinecraftWorld;
import org.cobblestonemc.minecraft.api.MinecraftStepPayload;
import org.cobblestonemc.minecraft.api.MinecraftStepType;

/**
 * A goal-aware fail-safe: when the agent is within throwing range of the leg's goal and still has
 * ender pearls, it can pearl straight to it — the only way to reach a floating target like an end
 * gateway without flight.
 *
 * <p>It is deliberately <b>expensive</b> ({@code cost} ≈ five minutes, the rough value of the
 * pearls an enderman drops) so A* only ever chooses it when there is no cheaper route — a genuine
 * last resort. Its real {@code time} is short (flight time of the throw). The pearls used so far
 * ride in the {@link MinecraftKeys#PEARLS_USED traversal state}, capped at the inventory count.
 *
 * <p>Whether the throw is actually clear (no blocks in the ballistic path) rides on the movement's
 * lazy {@link Movement#restricted() restricted} supplier, so those block lookups run only if the
 * search actually pops this edge — not for every cell within range.
 */
final class EnderPearl {

  private static final double RANGE = 32.0; // max throw distance, blocks
  private static final double MIN_RANGE = 2.0; // below this, just walk
  private static final double COST =
      300.0; // ~5 minutes: the value of a pearl (find + kill enderman)
  private static final double SPEED = 25.0; // pearl flight speed, blocks/second
  private static final double THROW_SECONDS = 2.0; // pull it to the hotbar and throw

  private EnderPearl() {}

  /** Offers a throw from {@code from} to {@code goal}, if it is in range and a pearl is left. */
  static void offer(
      MinecraftWorld world,
      Cell from,
      TraversalState state,
      Cell goal,
      int pearlCount,
      Edges edges) {
    Integer used = state.get(MinecraftKeys.PEARLS_USED);
    int usedCount = used == null ? 0 : used;
    if (usedCount >= pearlCount) {
      return; // out of pearls on this route
    }
    double distance = from.distance(goal);
    if (distance < MIN_RANGE || distance > RANGE) {
      return;
    }
    double time = distance / SPEED + THROW_SECONDS;
    TraversalState next = state.with(MinecraftKeys.PEARLS_USED, usedCount + 1);
    edges.add(
        new Movement<>(
            goal,
            COST,
            time,
            MinecraftStepPayload.of(MinecraftStepType.TELEPORT),
            next,
            ballisticCheck(world, from, goal)));
  }

  /** A lazy check that every block on the throw's line is passable (else the throw is blocked). */
  private static Supplier<FutureOr<Boolean>> ballisticCheck(
      MinecraftWorld world, Cell from, Cell to) {
    List<Cell> path = line(from, to);
    return () -> {
      List<FutureOr<Boolean>> passable = new ArrayList<>(path.size());
      for (Cell cell : path) {
        passable.add(world.blockAt(cell, null).map(MinecraftBlock::isPassable));
      }
      // restricted (true) if any cell along the throw is not passable.
      return FutureOr.all(passable).map(list -> list.contains(Boolean.FALSE));
    };
  }

  /** The cells stepped through from {@code from} to {@code to} (exclusive of {@code from}). */
  private static List<Cell> line(Cell from, Cell to) {
    int dx = to.x() - from.x();
    int dy = to.y() - from.y();
    int dz = to.z() - from.z();
    int steps = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
    List<Cell> cells = new ArrayList<>();
    if (steps == 0) {
      return cells;
    }
    Cell previous = from;
    for (int i = 1; i <= steps; i++) {
      Cell cell =
          new Cell(
              from.x() + (int) Math.round((double) dx * i / steps),
              from.y() + (int) Math.round((double) dy * i / steps),
              from.z() + (int) Math.round((double) dz * i / steps));
      if (!cell.equals(previous)) {
        cells.add(cell);
        previous = cell;
      }
    }
    return cells;
  }
}
