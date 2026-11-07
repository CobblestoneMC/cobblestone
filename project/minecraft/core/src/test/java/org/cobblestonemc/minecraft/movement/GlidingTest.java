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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.cobblestonemc.Cell;
import org.cobblestonemc.Movement;
import org.cobblestonemc.api.TraversalState;
import org.cobblestonemc.minecraft.MinecraftKeys;
import org.cobblestonemc.minecraft.TestBlocks;
import org.cobblestonemc.minecraft.TestMovements;
import org.cobblestonemc.minecraft.TestMovements.Moves;
import org.cobblestonemc.minecraft.TestPlayer;
import org.cobblestonemc.minecraft.TestWorld;
import org.cobblestonemc.minecraft.Threats;
import org.cobblestonemc.minecraft.api.CostWeights;
import org.cobblestonemc.minecraft.api.MinecraftStepPayload;
import org.cobblestonemc.minecraft.api.MinecraftStepType;
import org.junit.jupiter.api.Test;

/** Gliding on an elytra: taking off, boosting, gliding down and landing. */
class GlidingTest {

  private static final CostWeights WEIGHTS = CostWeights.defaults();
  private static final TraversalState GLIDING =
      TraversalState.DEFAULT.with(MinecraftKeys.GLIDING, true);
  private static final Cell HIGH = new Cell(0, 40, 0);

  private static MinecraftMovementBehavior behavior(TestPlayer player) {
    return MinecraftMovementBehavior.forPlayer(
        TestWorld.CHUNKS, player, Set.of(), null, 0, WEIGHTS, Threats.NONE);
  }

  private static TestWorld.Builder ground(String key) {
    return TestWorld.builder(key).floor(0, -8, -8, 8, 8, TestBlocks.solid());
  }

  private static Moves gliding(TestPlayer player, TestWorld world, Cell at) {
    return TestMovements.from(behavior(player), world, at, GLIDING);
  }

  @Test
  void aGliderOnTheGroundWalksAndDoesNotFly() {
    Abilities can = behavior(TestPlayer.glider().withRockets(2)).abilities();
    assertTrue(can.glide());
    assertEquals(2, can.rockets());
    assertEquals(0, can.flightHeight(), "a glider is not a flier");
    assertTrue(can.walk());
    assertTrue(can.fall());
  }

  @Test
  void withoutARocketAGliderOnlyGlidesDownOrCoasts() {
    Moves moves = gliding(TestPlayer.glider(), ground("sky").build(), HIGH);

    Movement<MinecraftStepPayload> down = moves.to(new Cell(1, 39, 0), MinecraftStepType.ELYTRA);
    assertEquals(MovementCosts.GLIDE_DOWN * Math.sqrt(2), down.time(), 1e-9);
    Movement<MinecraftStepPayload> level = moves.to(new Cell(1, 40, 0), MinecraftStepType.ELYTRA);
    assertEquals(MovementCosts.GLIDE_LEVEL, level.time(), 1e-9);
    assertFalse(moves.reaches(new Cell(0, 41, 0)), "no climbing without a boost");
    assertTrue(moves.all().stream().allMatch(m -> m.state().equals(GLIDING)), "still in the air");
  }

  @Test
  void aRocketBoostsAnyWayAndOpenSkyIsFree() {
    Moves moves = gliding(TestPlayer.glider().withRockets(1), ground("sky").build(), HIGH);

    Movement<MinecraftStepPayload> up = moves.to(new Cell(0, 41, 0), MinecraftStepType.ELYTRA);
    assertEquals(MovementCosts.BOOST, up.time(), 1e-9);
    assertEquals(up.time(), up.cost(), 1e-9);
    Movement<MinecraftStepPayload> level = moves.to(new Cell(1, 40, 0), MinecraftStepType.ELYTRA);
    assertEquals(MovementCosts.BOOST, level.time(), 1e-9, "a boost beats coasting");
  }

  @Test
  void boostingWithoutRoomCostsMoreTheCloserTheWall() {
    TestWorld wall = ground("wall").set(2, 40, 0, TestBlocks.solid()).build();
    Moves moves = gliding(TestPlayer.glider().withRockets(1), wall, HIGH);

    // Up and over: only a boost climbs, and it passes right beside the wall.
    Movement<MinecraftStepPayload> beside = moves.to(new Cell(1, 41, 0), MinecraftStepType.ELYTRA);
    double distance = Math.sqrt(2);
    assertEquals(MovementCosts.BOOST * distance, beside.time(), 1e-9);
    assertEquals(
        beside.time() + WEIGHTS.crampedGliding() * distance, beside.cost(), 1e-9, "full strength");

    // Two blocks of room: two thirds of it.
    Movement<MinecraftStepPayload> nearer = moves.to(new Cell(0, 41, 0), MinecraftStepType.ELYTRA);
    assertEquals(
        nearer.time() + WEIGHTS.crampedGliding() * 2.0 / 3.0, nearer.cost(), 1e-9, "two of four");
  }

  /** An end gateway is a one-block hole: coasting slips through where a boost would strike it. */
  @Test
  void aGliderCoastsThroughAOneBlockHole() {
    TestWorld.Builder slab = TestWorld.builder("gateway");
    for (int x = -3; x <= 3; x++) {
      for (int y = 37; y <= 43; y++) {
        if (x != 0 || y != 40) {
          slab.set(x, y, 1, TestBlocks.solid());
        }
      }
    }
    Movement<MinecraftStepPayload> through =
        gliding(TestPlayer.glider().withRockets(1), slab.build(), HIGH)
            .to(new Cell(0, 40, 1), MinecraftStepType.ELYTRA);

    assertNotNull(through, "a one-block-tall glider fits the hole");
    assertEquals(MovementCosts.GLIDE_LEVEL, through.time(), 1e-9);
    assertEquals(through.time(), through.cost(), 1e-9, "careful, not cramped");
  }

  @Test
  void reachingTheGroundLands() {
    Movement<MinecraftStepPayload> landing =
        gliding(TestPlayer.glider(), ground("land").build(), new Cell(0, 2, 0))
            .to(new Cell(1, 1, 0), MinecraftStepType.ELYTRA);

    assertNull(landing.state().get(MinecraftKeys.GLIDING));
  }

  @Test
  void steppingOffADropOpensTheElytra() {
    TestWorld ledge =
        TestWorld.builder("ledge").floor(10, -2, -2, 0, 2, TestBlocks.solid()).build();
    Moves moves = TestMovements.from(behavior(TestPlayer.glider()), ledge, new Cell(0, 11, 0));

    Movement<MinecraftStepPayload> off = moves.to(new Cell(1, 11, 0), MinecraftStepType.ELYTRA);
    assertNotNull(off);
    assertEquals(GLIDING, off.state());
    assertFalse(
        moves.ofType(MinecraftStepType.ELYTRA).all().stream()
            .anyMatch(m -> m.cell().x() <= 0 && m.cell().y() == 11),
        "not over the ground");
  }

  @Test
  void aRocketTakesOffAlongAClearLine() {
    List<Movement<MinecraftStepPayload>> takeoffs =
        TestMovements.from(
                behavior(TestPlayer.glider().withRockets(1)),
                ground("field").build(),
                new Cell(0, 1, 0))
            .all()
            .stream()
            .filter(m -> m.state().equals(GLIDING) && m.cell().y() > 2)
            .toList();

    assertEquals(1, takeoffs.size(), "one takeoff at most: " + takeoffs);
    Movement<MinecraftStepPayload> takeoff = takeoffs.getFirst();
    assertEquals(
        MovementCosts.BOOST * MovementCosts.TAKEOFF_BLOCKS_PER_DURATION, takeoff.time(), 1e-9);
    assertEquals(
        2 + Math.round(MovementCosts.TAKEOFF_BLOCKS_PER_DURATION * Math.sin(Math.toRadians(70))),
        takeoff.cell().y(),
        "the steepest climb, from the head");
  }

  @Test
  void noTakeoffUnderARoof() {
    TestWorld roofed = ground("roofed").floor(10, -60, -60, 60, 60, TestBlocks.solid()).build();
    Moves moves =
        TestMovements.from(behavior(TestPlayer.glider().withRockets(3)), roofed, new Cell(0, 1, 0));

    assertTrue(moves.all().stream().noneMatch(m -> m.state().equals(GLIDING)));
  }

  @Test
  void noTakeoffWithoutARocket() {
    Moves moves =
        TestMovements.from(
            behavior(TestPlayer.glider()), ground("field").build(), new Cell(0, 1, 0));

    assertTrue(moves.all().stream().noneMatch(m -> m.state().equals(GLIDING)));
  }

  @Test
  void theCheapestBlockIsABoost() {
    assertEquals(
        MovementCosts.BOOST, behavior(TestPlayer.glider().withRockets(1)).cheapestCostPerBlock());
    assertEquals(MovementCosts.GLIDE_DOWN, behavior(TestPlayer.glider()).cheapestCostPerBlock());
  }
}
