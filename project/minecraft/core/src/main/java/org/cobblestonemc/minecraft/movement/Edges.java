/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.movement;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.cobblestonemc.Cell;
import org.cobblestonemc.FutureOr;
import org.cobblestonemc.Movement;
import org.cobblestonemc.api.TraversalState;
import org.cobblestonemc.minecraft.Threats;
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
 * near dangerous terrain it passes, and how much the shore slows a boat; and from the search's
 * {@link Threats}, how near it passes a hostile mob or sculk.
 */
final class Edges {

  private final Map<Key, Movement<MinecraftStepPayload>> best = new LinkedHashMap<>();
  private final CostWeights weights;
  private final Surroundings surroundings;
  private final Threats threats;

  /**
   * Collects the movements out of one cell, pricing what is around it.
   *
   * @param weights what penalties are priced with
   * @param surroundings what is around the expanding cell
   * @param threats where mobs and sculk were when the search began
   */
  Edges(CostWeights weights, Surroundings surroundings, Threats threats) {
    this.weights = weights;
    this.surroundings = surroundings;
    this.threats = threats;
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
    if (type == MinecraftStepType.BOAT) {
      time *= 1 + MovementCosts.COAST_SLOWDOWN * surroundings.coast(cell);
    }
    // A fall is priced by the damage it does, not by the drop it goes over.
    if (type != MinecraftStepType.FALL) {
      penalty += weights.dangerousTerrain() * time * surroundings.danger(cell);
    }
    if (!threats.isEmpty()) {
      penalty +=
          time
              * (weights.dangerousEntity() * threats.mobs(cell, weights.dangerousEntityRadius())
                  + weights.sculk() * threats.sculk(cell, CostWeights.SCULK_RADIUS));
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
