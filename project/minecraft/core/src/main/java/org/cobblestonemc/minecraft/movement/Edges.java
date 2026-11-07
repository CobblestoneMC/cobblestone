/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.movement;

import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.cobblestonemc.Cell;
import org.cobblestonemc.FutureOr;
import org.cobblestonemc.Movement;
import org.cobblestonemc.api.TraversalState;
import org.cobblestonemc.minecraft.api.CostWeights;
import org.cobblestonemc.minecraft.api.MinecraftInstruction;
import org.cobblestonemc.minecraft.api.MinecraftStepPayload;
import org.cobblestonemc.minecraft.api.MinecraftStepType;
import org.jetbrains.annotations.Nullable;

/**
 * The movements one expansion offers, keeping only the cheapest edge per (cell, resulting state).
 * On a tie, an edge with no {@link Movement#restricted() restriction} wins.
 *
 * <p>Rules offer what a step takes in seconds, and what it does to the player; this prices the harm
 * with the search's {@link CostWeights} and adds it to the cost, never to the time. Where a step
 * ends up is priced here too, from the expansion's {@link Surroundings}, so no rule has to: how
 * near dangerous terrain it passes, how cramped a glide is, and how close a boat runs to the shore.
 * A step with momentum that changes the direction the route arrived in is charged for the turn.
 */
final class Edges {

  /** The steps with momentum to lose, so that changing direction during them is charged. */
  private static final Set<MinecraftStepType> MOMENTUM =
      EnumSet.of(
          MinecraftStepType.WALK,
          MinecraftStepType.JUMP,
          MinecraftStepType.SWIM,
          MinecraftStepType.FLY,
          MinecraftStepType.BOAT,
          MinecraftStepType.HORSE);

  private final Map<Key, Movement<MinecraftStepPayload>> best = new LinkedHashMap<>();
  private final CostWeights weights;
  private final Cell from;
  private final @Nullable Cell previous;
  private final Surroundings surroundings;

  /**
   * Collects the movements out of one cell, pricing what is around it.
   *
   * @param weights what penalties are priced with
   * @param from the cell the movements leave
   * @param previous the cell the route to {@code from} came from, or {@code null} if unknown
   * @param surroundings what is around the expanding cell
   */
  Edges(CostWeights weights, Cell from, @Nullable Cell previous, Surroundings surroundings) {
    this.weights = weights;
    this.from = from;
    this.previous = previous;
    this.surroundings = surroundings;
  }

  /** The weights this expansion's penalties are priced with. */
  CostWeights weights() {
    return weights;
  }

  /** Offers a harmless movement taking {@code time} seconds, with no instruction. */
  void add(Cell cell, double time, MinecraftStepType type, TraversalState state) {
    add(cell, time, type, state, null);
  }

  /** Offers a harmless movement carrying a lazily-checked restriction. */
  void add(
      Cell cell,
      double time,
      MinecraftStepType type,
      TraversalState state,
      @Nullable Supplier<FutureOr<Boolean>> restricted) {
    addPenalized(cell, time, 0.0, type, state, restricted);
  }

  /** Offers a movement taking {@code time} seconds that deals {@code halfHearts} of damage. */
  void addHurting(
      Cell cell, double time, double halfHearts, MinecraftStepType type, TraversalState state) {
    addPenalized(cell, time, weights.damage(halfHearts), type, state, null);
  }

  /**
   * Offers a movement taking {@code time} seconds that a rule has already found a {@code penalty}
   * for, on top of what its destination costs.
   */
  void addPenalized(
      Cell cell,
      double time,
      double penalty,
      MinecraftStepType type,
      TraversalState state,
      @Nullable Supplier<FutureOr<Boolean>> restricted) {
    // A fall is priced by the damage it does, not by the drop it goes over.
    if (type != MinecraftStepType.FALL) {
      penalty += weights.dangerousTerrain() * time * surroundings.danger(cell);
    }
    if (type == MinecraftStepType.FLY) {
      penalty += weights.crampedGliding() * time * surroundings.cramped(cell);
    } else if (type == MinecraftStepType.BOAT) {
      penalty += weights.coast() * time * surroundings.coast(cell);
    }
    if (MOMENTUM.contains(type) && turns(cell)) {
      penalty += weights.turn();
    }
    add(
        new Movement<>(
            cell,
            time + penalty,
            time,
            new MinecraftStepPayload(type, MinecraftInstruction.None.INSTANCE),
            state,
            restricted));
  }

  /**
   * Whether a step to {@code to} changes the horizontal direction the route arrived in. Only the
   * horizontal direction counts, so a step straight up or down neither turns nor is turned from,
   * and a route's first step has nothing to turn from.
   */
  private boolean turns(Cell to) {
    if (previous == null) {
      return false;
    }
    int inX = Integer.signum(from.x() - previous.x());
    int inZ = Integer.signum(from.z() - previous.z());
    int outX = Integer.signum(to.x() - from.x());
    int outZ = Integer.signum(to.z() - from.z());
    return (inX != 0 || inZ != 0) && (outX != 0 || outZ != 0) && (inX != outX || inZ != outZ);
  }

  /** Offers a fully built movement. */
  void add(Movement<MinecraftStepPayload> movement) {
    best.merge(
        new Key(movement.cell(), movement.state()),
        movement,
        (held, offered) -> better(offered, held) ? offered : held);
  }

  Collection<Movement<MinecraftStepPayload>> movements() {
    return best.values();
  }

  private static boolean better(
      Movement<MinecraftStepPayload> offered, Movement<MinecraftStepPayload> held) {
    if (offered.cost() != held.cost()) {
      return offered.cost() < held.cost();
    }
    return offered.restricted() == null && held.restricted() != null;
  }

  private record Key(Cell cell, TraversalState state) {}
}
