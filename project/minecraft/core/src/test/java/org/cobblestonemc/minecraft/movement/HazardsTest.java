/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import org.cobblestonemc.Cell;
import org.cobblestonemc.Movement;
import org.cobblestonemc.api.TraversalState;
import org.cobblestonemc.minecraft.MinecraftBlock;
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

/** The penalties a step pays for where it goes, on top of the time it takes. */
class HazardsTest {

  private static final CostWeights WEIGHTS = CostWeights.defaults();
  private static final Cell ORIGIN = new Cell(0, 1, 0);

  private static MinecraftMovementBehavior behavior(TestPlayer player) {
    return MinecraftMovementBehavior.forPlayer(
        TestWorld.CHUNKS, player, Set.of(), null, 0, CostWeights.defaults(), Threats.NONE);
  }

  /** Wide ground, so that nothing near the origin is the lip of a drop. */
  private static TestWorld.Builder ground(String key) {
    return TestWorld.builder(key).floor(0, -8, -8, 8, 8, TestBlocks.solid());
  }

  @Test
  void dangerousTerrainCostsByHowNearItPasses() {
    TestWorld world = ground("lava").set(3, 1, 0, TestBlocks.lava()).build();
    CostWeights wide = new CostWeights(8.0, 300.0, 1.0, 2, 10.0, 10.0, 5.0, 16, 50.0);
    Moves moves =
        TestMovements.from(
            MinecraftMovementBehavior.forPlayer(
                TestWorld.CHUNKS,
                TestPlayer.walker().vulnerable(),
                Set.of(),
                null,
                0,
                wide,
                Threats.NONE),
            world,
            ORIGIN);

    // Two blocks from the lava: half strength.
    Movement<MinecraftStepPayload> toward = moves.to(new Cell(1, 1, 0));
    assertEquals(MovementCosts.WALK, toward.time(), 1e-9);
    assertEquals(
        toward.time() + wide.dangerousTerrain() * toward.time() * 0.5, toward.cost(), 1e-9);
    // Four blocks away: past the radius.
    Movement<MinecraftStepPayload> away = moves.to(new Cell(-1, 1, 0));
    assertEquals(away.time(), away.cost(), 1e-9);
  }

  @Test
  void theLipOfAHarmfulDropIsDangerous() {
    // Ground only to x = 0; past it, nothing to land on.
    TestWorld world = TestWorld.builder("cliff").floor(0, -8, -8, 0, 8, TestBlocks.solid()).build();
    Movement<MinecraftStepPayload> alongTheEdge =
        TestMovements.from(behavior(TestPlayer.walker().vulnerable()), world, new Cell(0, 1, -1))
            .to(new Cell(0, 1, 0), MinecraftStepType.WALK);

    assertEquals(
        alongTheEdge.time() + WEIGHTS.dangerousTerrain() * alongTheEdge.time(),
        alongTheEdge.cost(),
        1e-9);
  }

  @Test
  void aLedgeIsNothingToAPlayerInTheAir() {
    // Hanging over the drop, and not standing beside it: there is nothing to walk off.
    TestWorld world = TestWorld.builder("over").floor(0, -8, -8, 0, 8, TestBlocks.solid()).build();
    Movement<MinecraftStepPayload> glide =
        TestMovements.from(
                behavior(TestPlayer.glider().vulnerable()),
                world,
                new Cell(4, 20, 0),
                TraversalState.DEFAULT.with(MinecraftKeys.GLIDING, true))
            .to(new Cell(5, 19, 0), MinecraftStepType.ELYTRA);

    assertEquals(glide.time(), glide.cost(), 1e-9);
  }

  @Test
  void aLedgeIsNothingToAPlayerWhoFlies() {
    // Right above the ground and beside the drop, which would charge anyone who could fall.
    TestWorld world = TestWorld.builder("lip").floor(0, -8, -8, 0, 8, TestBlocks.solid()).build();
    Movement<MinecraftStepPayload> flight =
        TestMovements.from(
                behavior(TestPlayer.create(true, false, false).vulnerable()),
                world,
                new Cell(0, 1, -1))
            .to(new Cell(0, 1, 0), MinecraftStepType.FLY);

    assertEquals(flight.time(), flight.cost(), 1e-9);
  }

  @Test
  void aPlayerWhoFliesIsStillChargedForLava() {
    TestWorld world = ground("lava-flyer").set(2, 1, 0, TestBlocks.lava()).build();
    Movement<MinecraftStepPayload> beside =
        TestMovements.from(
                behavior(TestPlayer.create(true, false, false).vulnerable()), world, ORIGIN)
            .to(new Cell(1, 1, 0), MinecraftStepType.FLY);

    assertEquals(beside.time() + WEIGHTS.dangerousTerrain() * beside.time(), beside.cost(), 1e-9);
  }

  @Test
  void aPlayerWhoCannotBeHurtIsNotChargedForDanger() {
    TestWorld world = ground("lava-creative").set(2, 1, 0, TestBlocks.lava()).build();
    Movement<MinecraftStepPayload> beside =
        TestMovements.from(behavior(TestPlayer.walker()), world, ORIGIN).to(new Cell(1, 1, 0));

    assertEquals(beside.time(), beside.cost(), 1e-9);
  }

  @Test
  void breakingABuildCostsMoreThanBreakingRock() {
    Movement<MinecraftStepPayload> throughRock =
        TestMovements.from(behavior(TestPlayer.walker()), wall("rock", TestBlocks.solid()), ORIGIN)
            .to(new Cell(1, 1, 0), MinecraftStepType.MINE);
    Movement<MinecraftStepPayload> throughPlanks =
        TestMovements.from(
                behavior(TestPlayer.walker()), wall("planks", TestBlocks.planks()), ORIGIN)
            .to(new Cell(1, 1, 0), MinecraftStepType.MINE);

    assertEquals(throughRock.time(), throughPlanks.time(), 1e-9);
    assertEquals(throughRock.cost() + 2 * WEIGHTS.builtBlock(), throughPlanks.cost(), 1e-9);
  }

  private static TestWorld wall(String key, MinecraftBlock block) {
    return ground(key).set(1, 1, 0, block).set(1, 2, 0, block).build();
  }

  @Test
  void boatingBesideTheShoreTakesLongerThanOffshore() {
    TestWorld world =
        TestWorld.builder("sea")
            .floor(0, -8, -8, 8, 8, TestBlocks.water())
            .set(2, 0, 0, TestBlocks.solid())
            .build();
    MinecraftMovementBehavior boater = behavior(TestPlayer.create(false, false, true));
    Moves moves =
        TestMovements.from(boater, world, new Cell(0, 0, 0), boater.initialState())
            .ofType(MinecraftStepType.BOAT);

    Movement<MinecraftStepPayload> ashore = moves.to(new Cell(1, 0, 0));
    assertEquals(MovementCosts.BOAT * (1 + MovementCosts.COAST_SLOWDOWN), ashore.time(), 1e-9);
    assertEquals(ashore.time(), ashore.cost(), 1e-9);
    Movement<MinecraftStepPayload> offshore = moves.to(new Cell(-1, 0, 0));
    assertEquals(MovementCosts.BOAT, offshore.time(), 1e-9);
  }

  /** A pool deep enough to swim across. */
  private static TestWorld pool(String key) {
    TestWorld.Builder pool = ground(key);
    for (int y = 1; y <= 4; y++) {
      pool.floor(y, -4, -4, 4, 4, TestBlocks.water());
    }
    return pool.build();
  }

  @Test
  void aMobOrSculkNearbyCostsByHowNearItWas() {
    Threats threats = Threats.builder().mob(new Cell(9, 1, 0)).sculk(new Cell(0, 1, -5)).build();
    Movement<MinecraftStepPayload> step =
        TestMovements.from(
                MinecraftMovementBehavior.forPlayer(
                    TestWorld.CHUNKS,
                    TestPlayer.walker().vulnerable(),
                    Set.of(),
                    null,
                    0,
                    WEIGHTS,
                    threats),
                ground("threats").build(),
                ORIGIN)
            .to(new Cell(1, 1, 0));

    double mob = (WEIGHTS.dangerousEntityRadius() + 1 - 8.0) / WEIGHTS.dangerousEntityRadius();
    double sculk = (CostWeights.SCULK_RADIUS + 1 - Math.sqrt(26)) / CostWeights.SCULK_RADIUS;
    assertEquals(
        step.time() + step.time() * (WEIGHTS.dangerousEntity() * mob + WEIGHTS.sculk() * sculk),
        step.cost(),
        1e-9);
  }

  @Test
  void aPlayerWhoCannotBeHurtIgnoresThreats() {
    Threats threats = Threats.builder().mob(new Cell(2, 1, 0)).build();
    Movement<MinecraftStepPayload> step =
        TestMovements.from(
                MinecraftMovementBehavior.forPlayer(
                    TestWorld.CHUNKS, TestPlayer.walker(), Set.of(), null, 0, WEIGHTS, threats),
                ground("threats-creative").build(),
                ORIGIN)
            .to(new Cell(1, 1, 0));

    assertEquals(step.time(), step.cost(), 1e-9);
  }

  @Test
  void depthStriderSwimsAtWalkingSpeed() {
    Movement<MinecraftStepPayload> swim =
        TestMovements.from(behavior(TestPlayer.walker().striding(3)), pool("strider"), ORIGIN)
            .to(new Cell(1, 1, 0), MinecraftStepType.SWIM);

    assertEquals(MovementCosts.WALK, swim.time(), 1e-9);
  }
}
