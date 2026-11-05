/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

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

  /** Seconds of breath, in blocks swum across without Respiration or Depth Strider. */
  private static final int BREATH = (int) (MovementCosts.BREATH_SECONDS / MovementCosts.SWIM);

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
    CostWeights wide = new CostWeights(8.0, 300.0, 0.05, 1.0, 2, 10.0, 10.0, 1.0, 5.0, 16, 50.0);
    Moves moves =
        TestMovements.from(
            MinecraftMovementBehavior.forPlayer(
                TestWorld.CHUNKS, TestPlayer.walker().vulnerable(), Set.of(), null, 0, wide),
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
        TestMovements.from(behavior(TestPlayer.glider().vulnerable()), world, new Cell(4, 20, 0))
            .to(new Cell(5, 20, 0), MinecraftStepType.FLY);

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
  void glidingInOpenSkyIsFree() {
    Movement<MinecraftStepPayload> glide =
        TestMovements.from(behavior(TestPlayer.glider()), ground("sky").build(), new Cell(0, 40, 0))
            .to(new Cell(1, 40, 0), MinecraftStepType.FLY);

    assertEquals(glide.time(), glide.cost(), 1e-9);
  }

  @Test
  void glidingBesideAWallOrUnderCoverIsCramped() {
    Cell high = new Cell(0, 40, 0);
    TestWorld wall = ground("wall").set(2, 40, 0, TestBlocks.solid()).build();
    Movement<MinecraftStepPayload> besideTheWall =
        TestMovements.from(behavior(TestPlayer.glider()), wall, high)
            .to(new Cell(1, 40, 0), MinecraftStepType.FLY);
    assertEquals(
        besideTheWall.time() + WEIGHTS.crampedGliding() * besideTheWall.time(),
        besideTheWall.cost(),
        1e-9);

    TestWorld canopy = ground("canopy").set(0, 55, 0, TestBlocks.solid()).build();
    Movement<MinecraftStepPayload> underCover =
        TestMovements.from(behavior(TestPlayer.glider()), canopy, high)
            .to(new Cell(-1, 40, 0), MinecraftStepType.FLY);
    assertEquals(
        underCover.time() + WEIGHTS.crampedGliding() * underCover.time(), underCover.cost(), 1e-9);
  }

  @Test
  void boatingBesideTheShoreCostsMoreThanOffshore() {
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
    assertEquals(ashore.time() + WEIGHTS.coast() * ashore.time(), ashore.cost(), 1e-9);
    Movement<MinecraftStepPayload> offshore = moves.to(new Cell(-1, 0, 0));
    assertEquals(offshore.time(), offshore.cost(), 1e-9);
  }

  /** A pool deep enough to swim across with the head under. */
  private static TestWorld pool(String key) {
    TestWorld.Builder pool = ground(key);
    for (int y = 1; y <= 4; y++) {
      pool.floor(y, -4, -4, 4, 4, TestBlocks.water());
    }
    return pool.build();
  }

  @Test
  void aBreathRunsOutAndThenEveryBlockDrowns() {
    TestPlayer swimmer = TestPlayer.walker().vulnerable();
    Cell across = new Cell(1, 1, 0);

    Movement<MinecraftStepPayload> first =
        TestMovements.from(behavior(swimmer), pool("first"), ORIGIN)
            .to(across, MinecraftStepType.SWIM);
    assertEquals(1, first.state().get(MinecraftKeys.HELD_BREATH));
    assertEquals(first.time(), first.cost(), 1e-9);

    TestWorld pool = pool("last");
    TraversalState holding = TraversalState.DEFAULT.with(MinecraftKeys.HELD_BREATH, BREATH);
    Movement<MinecraftStepPayload> drowning =
        TestMovements.from(behavior(swimmer), pool, ORIGIN, holding)
            .to(across, MinecraftStepType.SWIM);
    assertEquals(BREATH + 1, drowning.state().get(MinecraftKeys.HELD_BREATH));
    assertEquals(
        drowning.time()
            + WEIGHTS.damage(MovementCosts.DROWNING_HALF_HEARTS_PER_SECOND * drowning.time()),
        drowning.cost(),
        1e-9);

    // Already capped: counting on would only multiply the states.
    TraversalState capped = TraversalState.DEFAULT.with(MinecraftKeys.HELD_BREATH, BREATH + 1);
    assertEquals(
        BREATH + 1,
        TestMovements.from(behavior(swimmer), pool, ORIGIN, capped)
            .to(across, MinecraftStepType.SWIM)
            .state()
            .get(MinecraftKeys.HELD_BREATH));
  }

  @Test
  void respirationStretchesTheBreath() {
    TraversalState holding = TraversalState.DEFAULT.with(MinecraftKeys.HELD_BREATH, BREATH);
    Movement<MinecraftStepPayload> stillHolding =
        TestMovements.from(
                behavior(TestPlayer.walker().vulnerable().enchanted(1, 0)),
                pool("respiration"),
                ORIGIN,
                holding)
            .to(new Cell(1, 1, 0), MinecraftStepType.SWIM);

    assertEquals(stillHolding.time(), stillHolding.cost(), 1e-9);
  }

  @Test
  void surfacingTakesABreath() {
    TestWorld shallow = ground("shallow").floor(1, -4, -4, 4, 4, TestBlocks.water()).build();
    TraversalState holding = TraversalState.DEFAULT.with(MinecraftKeys.HELD_BREATH, 10);
    Movement<MinecraftStepPayload> surfaced =
        TestMovements.from(behavior(TestPlayer.walker().vulnerable()), shallow, ORIGIN, holding)
            .to(new Cell(1, 1, 0), MinecraftStepType.SWIM);

    assertNull(surfaced.state().get(MinecraftKeys.HELD_BREATH));
  }

  @Test
  void aPlayerWhoCannotDrownHoldsNoBreath() {
    Movement<MinecraftStepPayload> swim =
        TestMovements.from(behavior(TestPlayer.walker()), pool("creative-pool"), ORIGIN)
            .to(new Cell(1, 1, 0), MinecraftStepType.SWIM);

    assertFalse(swim.state().contains(MinecraftKeys.HELD_BREATH));
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
        TestMovements.from(behavior(TestPlayer.walker().enchanted(0, 3)), pool("strider"), ORIGIN)
            .to(new Cell(1, 1, 0), MinecraftStepType.SWIM);

    assertEquals(MovementCosts.WALK, swim.time(), 1e-9);
  }
}
