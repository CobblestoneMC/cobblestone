/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.minecraft.modes;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import net.whimxiqal.odyssey.Cell;
import net.whimxiqal.odyssey.DomainRegion;
import net.whimxiqal.odyssey.FutureOr;
import net.whimxiqal.odyssey.Movement;
import net.whimxiqal.odyssey.api.TraversalState;
import net.whimxiqal.odyssey.minecraft.MinecraftAgent;
import net.whimxiqal.odyssey.minecraft.MinecraftBlock;
import net.whimxiqal.odyssey.minecraft.MinecraftKeys;
import net.whimxiqal.odyssey.minecraft.MinecraftWorld;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepPayload;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepType;

/**
 * A goal-aware fail-safe: when the agent is within throwing range of the leg's target and still has
 * ender pearls, it can pearl straight to it — the only way to reach a floating target like an end
 * gateway without flight. Unlike the local modes, this one needs to know where the leg is headed,
 * so it is built per leg with the target injected (via {@code ModesProvider}).
 *
 * <p>It is deliberately <b>expensive</b> ({@code cost} ≈ five minutes, the rough value of the
 * pearls an enderman drops) so A* only ever chooses it when there is no cheaper route — a genuine
 * last resort. Its real {@code time} is short (flight time of the throw). The pearls used so far
 * ride in the {@link MinecraftKeys#PEARLS_USED traversal state}, capped at the inventory count.
 *
 * <p>Whether the throw is actually clear (no blocks in the ballistic path) rides on the movement's
 * lazy {@link Movement#restricted() restricted} supplier, so those block lookups run only if the
 * search actually pops this edge — not for every cell within range.
 *
 * @param <A> the agent type
 */
final class EnderPearlMode<A extends MinecraftAgent> extends AbstractMinecraftMode<A> {

  private static final double RANGE = 32.0; // max throw distance, blocks
  private static final double MIN_RANGE = 2.0; // below this, just walk
  private static final double COST =
      300.0; // ~5 minutes: the value of a pearl (find + kill enderman)
  private static final double SPEED = 25.0; // pearl flight speed, blocks/second
  private static final double THROW_SECONDS = 2.0; // pull it to the hotbar and throw

  private final DomainRegion<MinecraftWorld> target;
  private final int pearlCount;

  EnderPearlMode(DomainRegion<MinecraftWorld> target, int pearlCount) {
    this.target = target;
    this.pearlCount = pearlCount;
  }

  @Override
  protected Set<Cell> requiredCells(Cell from) {
    return Set.of(); // no local blocks — the ballistic check runs in the restricted supplier
  }

  @Override
  protected FutureOr<Collection<Movement<MinecraftStepPayload>>> movements(
      A agent, Cell from, MinecraftWorld world, TraversalState state, BlockView view) {
    Integer used = state.get(MinecraftKeys.PEARLS_USED);
    int usedCount = used == null ? 0 : used;
    if (usedCount >= pearlCount) {
      return FutureOr.of(List.of()); // out of pearls on this route
    }
    Cell to = target.nearestBoundaryCell(from);
    double distance = from.distance(to);
    if (distance < MIN_RANGE || distance > RANGE) {
      return FutureOr.of(List.of());
    }
    double time = distance / SPEED + THROW_SECONDS;
    TraversalState next = state.with(MinecraftKeys.PEARLS_USED, usedCount + 1);
    Movement<MinecraftStepPayload> move =
        new Movement<>(
            to,
            COST,
            time,
            MinecraftStepPayload.of(MinecraftStepType.TELEPORT),
            next,
            ballisticCheck(world, from, to));
    return FutureOr.of(List.of(move));
  }

  /** A lazy check that every block on the throw's line is passable (else the throw is blocked). */
  private static Supplier<FutureOr<Boolean>> ballisticCheck(
      MinecraftWorld world, Cell from, Cell to) {
    List<Cell> path = line(from, to);
    return () -> {
      List<FutureOr<Boolean>> passable = new ArrayList<>(path.size());
      for (Cell cell : path) {
        passable.add(world.blockAt(cell).map(MinecraftBlock::isPassable));
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
