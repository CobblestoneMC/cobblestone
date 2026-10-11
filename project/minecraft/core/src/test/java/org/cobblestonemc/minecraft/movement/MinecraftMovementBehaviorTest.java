/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.cobblestonemc.Cell;
import org.cobblestonemc.Movement;
import org.cobblestonemc.api.TraversalState;
import org.cobblestonemc.minecraft.CobblestonePlayer;
import org.cobblestonemc.minecraft.MinecraftKeys;
import org.cobblestonemc.minecraft.TestBlocks;
import org.cobblestonemc.minecraft.TestMovements;
import org.cobblestonemc.minecraft.TestMovements.Moves;
import org.cobblestonemc.minecraft.TestPlayer;
import org.cobblestonemc.minecraft.TestWorld;
import org.cobblestonemc.minecraft.api.MinecraftStepPayload;
import org.cobblestonemc.minecraft.api.MinecraftStepType;
import org.junit.jupiter.api.Test;

class MinecraftMovementBehaviorTest {

  private static final CobblestonePlayer FLIER = TestPlayer.create(true, false, false, true);

  private static Abilities abilitiesOf(CobblestonePlayer player, MinecraftStepType... excluded) {
    return MinecraftMovementBehavior.forPlayer(player, Set.of(excluded)).abilities();
  }

  @Test
  void aWalkerWalksFallsAndClimbsButDoesNotFly() {
    Abilities can = abilitiesOf(TestPlayer.walker());
    assertTrue(can.walk());
    assertTrue(can.fall());
    assertTrue(can.climb());
    assertEquals(0, can.flightHeight());
  }

  /**
   * Flight is cheaper per block than walking, falling and climbing and reaches a superset of their
   * cells, so a player who can fly gains nothing from them.
   */
  @Test
  void aFlierSkipsWhatFlightDominates() {
    Abilities can = abilitiesOf(FLIER);
    assertEquals(2, can.flightHeight());
    assertFalse(can.walk(), "flight is cheaper than walking and reaches more");
    assertFalse(can.fall(), "a flier never needs to fall");
    assertFalse(can.climb(), "flight is cheaper than climbing");
  }

  @Test
  void aFlierKeepsWhatFlightCannotReplace() {
    Abilities can = abilitiesOf(FLIER);
    assertTrue(can.swim(), "flight cannot enter water");
    assertTrue(can.doors(), "a flier still needs a closed door opened");
    assertTrue(can.mine(), "a flier sealed in a room still needs a way out");
  }

  /** {@code -no-fly} means there is no flight to dominate anything, so walking returns. */
  @Test
  void excludingFlightBringsWalkingBack() {
    Abilities can = abilitiesOf(FLIER, MinecraftStepType.FLY);
    assertEquals(0, can.flightHeight());
    assertTrue(can.walk());
    assertTrue(can.fall());
    assertTrue(can.climb());
  }

  /**
   * Gliding is not flight — a glider cannot hover or gain height — so it dominates nothing and the
   * ground movements must stay.
   */
  @Test
  void aGliderKeepsWalking() {
    Abilities can = abilitiesOf(TestPlayer.glider());
    assertEquals(1, can.flightHeight(), "the glider is modelled as a 1-tall flier");
    assertTrue(can.walk());
    assertTrue(can.fall());
  }

  @Test
  void excludingWalkingAlsoExcludesDoorsAndMining() {
    Abilities can = abilitiesOf(TestPlayer.walker(), MinecraftStepType.WALK);
    assertFalse(can.walk());
    assertFalse(can.doors());
    assertFalse(can.mine());
  }

  @Test
  void aFlierOnFlatGroundFliesEverywhere() {
    TestWorld world = TestWorld.builder("w").floor(0, -1, -1, 1, 1, TestBlocks.solid()).build();
    Moves moves =
        TestMovements.from(
            MinecraftMovementBehavior.forPlayer(FLIER, Set.of()), FLIER, world, new Cell(0, 1, 0));
    assertTrue(moves.ofType(MinecraftStepType.WALK).isEmpty());
    assertEquals(17, moves.ofType(MinecraftStepType.FLY).size(), "every neighbor but the floor");
  }

  /**
   * A glider can both walk and fly to each neighbor on flat ground. Only one edge per neighbor is
   * handed to the search — the cheaper flight.
   */
  @Test
  void oneEdgePerDestinationAndTheCheapestWins() {
    CobblestonePlayer glider = TestPlayer.glider();
    TestWorld world = TestWorld.builder("w").floor(0, -1, -1, 1, 1, TestBlocks.solid()).build();
    Moves moves =
        TestMovements.from(
            MinecraftMovementBehavior.forPlayer(glider, Set.of()),
            glider,
            world,
            new Cell(0, 1, 0));

    Set<Cell> seen = new HashSet<>();
    for (Movement<MinecraftStepPayload> movement : moves.all()) {
      assertTrue(seen.add(movement.cell()), "a second edge into " + movement.cell());
    }
    assertTrue(moves.ofType(MinecraftStepType.WALK).isEmpty(), "flying is cheaper than walking");
  }

  @Test
  void anEnderPearlReachesAGoalInRange() {
    CobblestonePlayer player = TestPlayer.walker();
    TestWorld world = TestWorld.builder("w").floor(0, -1, -1, 1, 1, TestBlocks.solid()).build();
    Cell goal = new Cell(10, 8, 0);
    Collection<Movement<MinecraftStepPayload>> movements =
        MinecraftMovementBehavior.forPlayer(player, Set.of(), null, 1)
            .movements(player, new Cell(0, 1, 0), world, TraversalState.DEFAULT, goal)
            .value();

    List<Movement<MinecraftStepPayload>> thrown =
        movements.stream().filter(m -> m.cell().equals(goal)).toList();
    assertEquals(1, thrown.size());
    Movement<MinecraftStepPayload> pearl = thrown.get(0);
    assertEquals(MinecraftStepType.TELEPORT, pearl.payload().stepType());
    assertEquals(1, pearl.state().get(MinecraftKeys.PEARLS_USED));
    assertNotNull(pearl.restricted(), "the throw's path is checked lazily");
  }

  @Test
  void theCheapestCostPerBlockFollowsTheAbilities() {
    assertEquals(
        MovementCosts.FLY,
        MinecraftMovementBehavior.forPlayer(FLIER, Set.of()).cheapestCostPerBlock(),
        1e-9);
    assertEquals(
        MovementCosts.FALL_PER_BLOCK,
        MinecraftMovementBehavior.forPlayer(TestPlayer.walker(), Set.of(MinecraftStepType.HORSE))
            .cheapestCostPerBlock(),
        1e-9);
  }
}
