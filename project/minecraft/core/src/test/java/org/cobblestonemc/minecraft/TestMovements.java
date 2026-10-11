/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.List;
import org.cobblestonemc.Cell;
import org.cobblestonemc.FutureOr;
import org.cobblestonemc.Movement;
import org.cobblestonemc.MovementBehavior;
import org.cobblestonemc.api.TraversalState;
import org.cobblestonemc.minecraft.api.MinecraftStepPayload;
import org.cobblestonemc.minecraft.api.MinecraftStepType;

/** Test helper: runs one immediate expansion of a behavior and looks up what it offered. */
public final class TestMovements {

  private TestMovements() {}

  public static Moves from(
      MovementBehavior<CobblestonePlayer, MinecraftStepPayload, MinecraftWorld> behavior,
      CobblestonePlayer player,
      TestWorld world,
      Cell origin,
      TraversalState state) {
    FutureOr<Collection<Movement<MinecraftStepPayload>>> result =
        // A test world serves every block from memory, so the goal — which only steers chunk
        // read-ahead and the ender pearl — is set to the origin, out of any throw's range.
        behavior.movements(player, origin, world, state, origin);
    assertTrue(result.isImmediate(), "test worlds serve blocks immediately");
    return new Moves(List.copyOf(result.value()));
  }

  public static Moves from(
      MovementBehavior<CobblestonePlayer, MinecraftStepPayload, MinecraftWorld> behavior,
      CobblestonePlayer player,
      TestWorld world,
      Cell origin) {
    return from(behavior, player, world, origin, TraversalState.DEFAULT);
  }

  /**
   * The movements of one expansion.
   *
   * @param all every movement offered
   */
  public record Moves(List<Movement<MinecraftStepPayload>> all) {

    /** The movements of the given type. */
    public Moves ofType(MinecraftStepType type) {
      return new Moves(all.stream().filter(m -> m.payload().stepType() == type).toList());
    }

    /** The cheapest movement into {@code cell}, whatever its type and state, or {@code null}. */
    public Movement<MinecraftStepPayload> to(Cell cell) {
      Movement<MinecraftStepPayload> cheapest = null;
      for (Movement<MinecraftStepPayload> movement : all) {
        if (movement.cell().equals(cell)
            && (cheapest == null || movement.cost() < cheapest.cost())) {
          cheapest = movement;
        }
      }
      return cheapest;
    }

    /** The movement of the given type into {@code cell}, or {@code null}. */
    public Movement<MinecraftStepPayload> to(Cell cell, MinecraftStepType type) {
      return ofType(type).to(cell);
    }

    public boolean reaches(Cell cell) {
      return to(cell) != null;
    }

    public boolean reaches(Cell cell, MinecraftStepType type) {
      return to(cell, type) != null;
    }

    public int size() {
      return all.size();
    }

    public boolean isEmpty() {
      return all.isEmpty();
    }
  }
}
