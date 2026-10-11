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
 * The movements one expansion offers, keeping only the cheapest edge per (cell, resulting state).
 * On a tie, an edge with no {@link Movement#restricted() restriction} wins.
 */
final class Edges {

  private final Map<Key, Movement<MinecraftStepPayload>> best = new LinkedHashMap<>();

  /** Offers a movement with no instruction. */
  void add(Cell cell, double cost, MinecraftStepType type, TraversalState state) {
    add(cell, cost, type, state, null);
  }

  /** Offers a movement carrying a lazily-checked restriction. */
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
