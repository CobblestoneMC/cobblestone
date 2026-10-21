/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.navigator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.audience.Audience;
import org.cobblestonemc.CobblestoneLogger;
import org.cobblestonemc.api.Path;
import org.cobblestonemc.api.Step;
import org.cobblestonemc.minecraft.api.MinecraftStepPayload;
import org.cobblestonemc.minecraft.api.MinecraftStepType;
import org.cobblestonemc.plugin.api.Navigator;
import org.cobblestonemc.plugin.hologram.TrailLabels;
import org.cobblestonemc.plugin.message.Messages;
import org.junit.jupiter.api.Test;

/** How {@link AbstractTrailNavigator} smooths and draws its trail, and where its labels go. */
class AbstractTrailNavigatorTest {

  private static final UUID PLAYER = UUID.randomUUID();
  private static final double LEAD = TrailShape.DEFAULT.leadBlocks();

  @Test
  void minedNodesStayAtTheBlockTheyMine() {
    // A zigzag across a flat field where step 4 mines its way in. Its destination is off the
    // diagonal, so it would move if it were free; instead the trail runs straight through it.
    List<Step<Vec3, MinecraftStepPayload>> steps = new ArrayList<>();
    for (int i = 1; i <= 12; i++) {
      MinecraftStepType type = i == 5 ? MinecraftStepType.MINE : MinecraftStepType.WALK;
      steps.add(new Step<>(new Vec3((i + 1) / 2, 0, i / 2), 1, 1, MinecraftStepPayload.of(type)));
    }
    var navigator = new TestNavigator(new Path<>(new Vec3(0, 0, 0), steps));

    navigator.tick();

    Vec3 mined = navigator.renderPoint(steps.get(4).position());
    assertEquals(mined, navigator.trailNode(5), "the mined node is pinned");
    for (int node : new int[] {4, 6}) {
      Vec3 original = navigator.renderPoint(steps.get(node - 1).position());
      assertTrue(
          navigator.trailNode(node).minus(original).length() > 0.1,
          "node " + node + " beside it still smooths");
    }
  }

  @Test
  void theTrailStartsAFewBlocksAheadOfAPlayerStandingStill() {
    var navigator = new TestNavigator(straightPath(20, -1));
    navigator.tick();

    Vec3 head = navigator.trailHead();
    assertEquals(0.5 + LEAD, head.x(), 1e-6, "along the trail");
    assertEquals(0.9, head.y(), 1e-9, "at the trail's height");
    assertEquals(0.5, head.z(), 1e-9, "on the straight trail");
  }

  @Test
  void theTrailStartsFurtherAheadOfAMovingPlayer() {
    var navigator = new TestNavigator(straightPath(40, -1));
    for (int tick = 0; tick < 60; tick++) {
      navigator.player = new Vec3(0.5 + tick * 0.4, 0, 0.5); // 8 blocks a second
      navigator.tick();
    }

    double gap = navigator.trailHead().x() - navigator.player.x();
    // Measured along the trail from the point of it nearest the player, a little ahead of them.
    assertEquals(TrailShape.DEFAULT.leadSeconds() * 8, gap, 0.5, "a second's travel ahead");
  }

  @Test
  void noParticlesBetweenThePlayerAndTheTrailHead() {
    var navigator = new TestNavigator(straightPath(20, -1));
    navigator.tickMany(200);

    double nearest = navigator.particles.stream().mapToDouble(Vec3::x).min().orElseThrow();
    assertTrue(nearest > 0.5 + LEAD - 0.8, "nearest at x " + nearest);
  }

  @Test
  void aroundACornerTheTrailHeadStaysJustPastWhatThePlayerCanSee() {
    // Two steps east, then north up a corridor whose west wall hides it from the player.
    List<Step<Vec3, MinecraftStepPayload>> steps = new ArrayList<>();
    steps.add(walk(new Vec3(1, 0, 0)));
    steps.add(walk(new Vec3(2, 0, 0)));
    for (int z = -1; z >= -12; z--) {
      steps.add(walk(new Vec3(2, 0, z)));
    }
    TrailShape far = new TrailShape(12, 0, 3); // well up the corridor
    var open = new TestNavigator(new Path<>(new Vec3(0, 0, 0), steps), far);
    open.tick();
    assertTrue(open.trailHead().z() < -5, "without the wall: " + open.trailHead());

    var walled = new TestNavigator(new Path<>(new Vec3(0, 0, 0), steps), far);
    walled.probe = (x, y, z) -> y < 0 || (x <= 1 && z <= -1) ? TrailBlock.SOLID : TrailBlock.OPEN;
    walled.tick();
    Vec3 head = walled.trailHead();
    assertTrue(head.z() < 0.5 && head.z() > -2.0, "a stub around the corner: " + head);
  }

  @Test
  void withNoLeadTheTrailStartsAtThePlayer() {
    var navigator = new TestNavigator(straightPath(20, -1), new TrailShape(0, 0, 3));
    navigator.player = new Vec3(4.5, 0, 0.5);
    navigator.tick();
    assertEquals(4.5, navigator.trailHead().x(), 1e-6);
  }

  @Test
  void theTrailStopsShortOfADestinationInSight() {
    var navigator = new TestNavigator(straightPath(10, -1));
    navigator.tickMany(200);

    double furthest = navigator.particles.stream().mapToDouble(Vec3::x).max().orElseThrow();
    double goal = 10.5;
    assertTrue(furthest < goal - TrailShape.DEFAULT.goalClearance() + 0.8, "at x " + furthest);
  }

  @Test
  void withNoClearanceTheTrailRunsToTheDestination() {
    var navigator = new TestNavigator(straightPath(10, -1), new TrailShape(3, 1, 0));
    navigator.tickMany(200);

    double furthest = navigator.particles.stream().mapToDouble(Vec3::x).max().orElseThrow();
    assertTrue(furthest > 9.5, "at x " + furthest);
  }

  @Test
  void theTrailRunsToADestinationOutOfSight() {
    var navigator = new TestNavigator(straightPath(10, -1));
    // A wall at x = 8 from head height up: the trail passes under it, but the player can't see
    // the destination over it.
    navigator.probe = (x, y, z) -> y < 0 || (x == 8 && y >= 1) ? TrailBlock.SOLID : TrailBlock.OPEN;
    navigator.tickMany(200);

    double furthest = navigator.particles.stream().mapToDouble(Vec3::x).max().orElseThrow();
    assertTrue(furthest > 9.5, "at x " + furthest);
  }

  @Test
  void noGlitterAtTheDestination() {
    var navigator = new TestNavigator(straightPath(10, -1));
    navigator.tickMany(50);
    assertEquals(0, navigator.highlights.size());
  }

  @Test
  void aDoorIsHighlightedEvenInsideTheGap() {
    var navigator = new TestNavigator(straightPath(10, 2)); // the third step opens a door
    navigator.tickMany(50);

    assertFalse(navigator.highlights.isEmpty());
    assertTrue(navigator.highlights.stream().allMatch(point -> point.x() < 3.5), "at the door");
  }

  @Test
  void theTrailHeadCarriesOnThroughADoor() {
    var navigator = new TestNavigator(straightPath(20, 1)); // the second step opens a door
    navigator.tick();
    assertEquals(0.5 + LEAD, navigator.trailHead().x(), 1e-6);
  }

  @Test
  void labelPointIsTheTrailHead() {
    var navigator = new TestNavigator(straightPath(20, -1));
    navigator.tick();
    assertEquals(Optional.of(navigator.trailHead()), navigator.labelPoint());
  }

  @Test
  void offTheTrailBeforeTheGuideIsFoundTheLabelPointIsOnTheTrail() {
    var navigator = new TestNavigator(straightPath(10, -1));
    navigator.player = new Vec3(2.5, 0, 6.5);
    navigator.tick();

    Vec3 point = navigator.labelPoint().orElseThrow();
    assertEquals(0.5, point.z(), 1e-6, "on the trail");
    assertEquals(2.5 + LEAD, point.x(), 0.1, "ahead of the point of it nearest the player");
  }

  @Test
  void theTrailHeadFollowsTheGuideBackToTheTrail() {
    var navigator = new TestNavigator(straightPath(10, -1));
    navigator.player = new Vec3(2.5, 0, 6.5); // off to the side of the trail
    navigator.tick();

    // A guide straight back toward the trail, as the guide search would find.
    List<Step<Vec3, MinecraftStepPayload>> guide = new ArrayList<>();
    for (int z = 5; z >= 1; z--) {
      guide.add(walk(new Vec3(2, 0, z)));
    }
    navigator.setGuidePath(new Path<>(new Vec3(2, 0, 6), guide));
    navigator.tick();

    Vec3 point = navigator.labelPoint().orElseThrow();
    assertEquals(2.5, point.x(), 1e-6, "on the guide");
    // The guide is drawn from the player's feet, up to the trail's height, so a little less far.
    assertEquals(6.5 - LEAD, point.z(), 0.5, "toward the trail");
  }

  @Test
  void eachLabelPointIsTurnedOffOnItsOwn() {
    var navigator = new TestNavigator(straightPath(10, -1));
    navigator.showTrailLabel.set(false);
    navigator.tick();
    assertEquals(Optional.empty(), navigator.labelPoint());
    assertTrue(navigator.goalPoint().isPresent());

    navigator.showTrailLabel.set(true);
    navigator.showDestinationLabel.set(false);
    assertTrue(navigator.labelPoint().isPresent());
    assertEquals(Optional.empty(), navigator.goalPoint());
  }

  @Test
  void goalPointIsTheEndOfTheRoute() {
    var navigator = new TestNavigator(straightPath(10, -1));
    navigator.tick();
    assertEquals(Optional.of(new Vec3(10.5, 0.9, 0.5)), navigator.goalPoint());
  }

  @Test
  void attachesItsLabelWhileTheTripRuns() {
    List<String> calls = new ArrayList<>();
    TrailLabels<Vec3> labels =
        new TrailLabels<>() {
          @Override
          public void attach(UUID player, Navigator<Vec3> navigator, Source<Vec3> source) {
            calls.add("attach " + player);
          }

          @Override
          public void detach(UUID player, Navigator<Vec3> navigator) {
            calls.add("detach " + player);
          }
        };
    var navigator = new TestNavigator(straightPath(10, -1), labels);

    navigator.start();
    navigator.stop();
    assertEquals(List.of("attach " + PLAYER, "detach " + PLAYER), calls);
  }

  private static Step<Vec3, MinecraftStepPayload> walk(Vec3 to) {
    return new Step<>(to, 1, 1, MinecraftStepPayload.of(MinecraftStepType.WALK));
  }

  /** {@code length} walked steps east from the origin; step {@code door} (if any) opens a door. */
  private static Path<Vec3, MinecraftStepPayload> straightPath(int length, int door) {
    List<Step<Vec3, MinecraftStepPayload>> steps = new ArrayList<>();
    for (int i = 0; i < length; i++) {
      MinecraftStepType type = i == door ? MinecraftStepType.OPEN_DOOR : MinecraftStepType.WALK;
      steps.add(new Step<>(new Vec3(i + 1, 0, 0), 1, 1, MinecraftStepPayload.of(type)));
    }
    return new Path<>(new Vec3(0, 0, 0), steps);
  }

  /**
   * A navigator over a flat floor at y = -1, in one world, with the player at the origin unless
   * moved.
   */
  private static final class TestNavigator extends AbstractTrailNavigator<Vec3> {

    Vec3 player = new Vec3(0.5, 0, 0.5);
    TrailSmoother.BlockProbe probe = (x, y, z) -> y < 0 ? TrailBlock.SOLID : TrailBlock.OPEN;
    final AtomicBoolean showTrailLabel;
    final AtomicBoolean showDestinationLabel;
    final List<Vec3> particles = new ArrayList<>();
    final List<Vec3> highlights = new ArrayList<>();

    TestNavigator(Path<Vec3, MinecraftStepPayload> path) {
      this(path, TrailLabels.none(), TrailShape.DEFAULT);
    }

    TestNavigator(Path<Vec3, MinecraftStepPayload> path, TrailShape shape) {
      this(path, TrailLabels.none(), shape);
    }

    TestNavigator(Path<Vec3, MinecraftStepPayload> path, TrailLabels<Vec3> labels) {
      this(path, labels, TrailShape.DEFAULT);
    }

    private TestNavigator(
        Path<Vec3, MinecraftStepPayload> path, TrailLabels<Vec3> labels, TrailShape shape) {
      this(path, labels, shape, new AtomicBoolean(true), new AtomicBoolean(true));
    }

    private TestNavigator(
        Path<Vec3, MinecraftStepPayload> path,
        TrailLabels<Vec3> labels,
        TrailShape shape,
        AtomicBoolean showTrailLabel,
        AtomicBoolean showDestinationLabel) {
      super(
          64,
          1,
          0,
          new Messages(Locale.ROOT, false, new SilentLogger()),
          Locale.ROOT,
          labels,
          shape,
          showTrailLabel::get,
          showDestinationLabel::get);
      this.showTrailLabel = showTrailLabel;
      this.showDestinationLabel = showDestinationLabel;
      setPath(path);
    }

    @Override
    protected Vec3 location(Vec3 point) {
      return point;
    }

    @Override
    protected UUID playerId() {
      return PLAYER;
    }

    @Override
    protected boolean playerOnline() {
      return true;
    }

    @Override
    protected Vec3 playerPoint() {
      return player;
    }

    @Override
    protected Vec3 playerEye() {
      return player.plus(new Vec3(0, 1.62, 0));
    }

    void tickMany(int ticks) {
      for (int i = 0; i < ticks; i++) {
        tick();
      }
    }

    @Override
    protected String playerWorldKey() {
      return "world";
    }

    @Override
    protected Vec3 renderPoint(Vec3 location) {
      return location.plus(new Vec3(0.5, 0.9, 0.5));
    }

    @Override
    protected String worldKey(Vec3 location) {
      return "world";
    }

    @Override
    protected Audience audience() {
      return Audience.empty();
    }

    @Override
    protected void spawnTrailParticle(
        double x, double y, double z, double vx, double vy, double vz) {
      particles.add(new Vec3(x, y, z));
    }

    @Override
    protected void spawnHighlightParticle(double x, double y, double z) {
      highlights.add(new Vec3(x, y, z));
    }

    @Override
    protected TrailSmoother.BlockProbe blockProbe() {
      return probe;
    }
  }

  private static final class SilentLogger extends CobblestoneLogger {
    @Override
    public void trace(String message, Object... args) {}

    @Override
    public void debug(String message, Object... args) {}

    @Override
    public void info(String message, Object... args) {}

    @Override
    public void warn(String message, Object... args) {}

    @Override
    public void error(String message, Throwable throwable, Object... args) {}
  }
}
