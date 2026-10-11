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
import org.cobblestonemc.minecraft.api.MinecraftInstruction;
import org.cobblestonemc.minecraft.api.MinecraftStepPayload;
import org.cobblestonemc.minecraft.api.MinecraftStepType;
import org.jetbrains.annotations.Nullable;

/**
 * The movements one expansion offers, keeping a single edge per destination: the cheapest way to
 * reach each (cell, resulting state).
 *
 * <p>Several rules can reach the same cell — a walker and a swimmer both into shallow water, a door
 * and a pickaxe through the same wall — and the search retains one edge per parent anyway, so only
 * the cheapest is worth handing it. Edges that leave the agent in different states (on foot versus
 * in a boat) lead to different search nodes, and are kept apart.
 *
 * <p>On a tie, an unconditional edge beats one carrying a {@link Movement#restricted() restriction}
 * — it can never be withdrawn.
 */
final class Edges {

  private final Map<Key, Movement<MinecraftStepPayload>> best = new LinkedHashMap<>();

  /** Offers a movement with no instruction. Time equals cost until danger weighting diverges. */
  void add(Cell cell, double cost, MinecraftStepType type, TraversalState state) {
    add(cell, cost, type, state, null);
  }

  /** Offers a movement carrying a lazily-checked edge restriction (see {@link Movement}). */
  void add(
      Cell cell,
      double cost,
      MinecraftStepType type,
      TraversalState state,
      @Nullable Supplier<FutureOr<Boolean>> restricted) {
    add(
        new Movement<>(
            cell,
            cost,
            cost,
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
