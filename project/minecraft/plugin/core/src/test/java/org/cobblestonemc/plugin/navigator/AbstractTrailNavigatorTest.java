/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.navigator;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

/** How {@link AbstractTrailNavigator} decides which trail nodes the smoother may move. */
class AbstractTrailNavigatorTest {

  private static final UUID PLAYER = UUID.randomUUID();

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
  void labelPointIsOnTheTrailAtTheGivenDistance() {
    var navigator = new TestNavigator(straightPath(10, -1));
    navigator.tick();

    Vec3 point = navigator.labelPoint(2.5).orElseThrow();
    assertEquals(2.5, point.minus(navigator.player).length(), 1e-9);
    assertEquals(0.9, point.y(), 1e-9, "at the trail's height");
    assertEquals(0.5, point.z(), 1e-9, "on the straight trail");
    assertTrue(point.x() > navigator.player.x(), "ahead of the player");
  }

  @Test
  void labelPointFollowsThePlayerAlongTheTrail() {
    var navigator = new TestNavigator(straightPath(10, -1));
    navigator.player = new Vec3(4.5, 0, 0.5);
    navigator.tick();

    Vec3 point = navigator.labelPoint(2.5).orElseThrow();
    assertTrue(point.x() > 6.5 && point.x() < 7.0, "2.5 blocks past the player: " + point);
  }

  @Test
  void noLabelPointOffTheTrail() {
    var navigator = new TestNavigator(straightPath(10, -1));
    navigator.tick();
    navigator.player = new Vec3(2.5, 0, 6.5);

    assertEquals(Optional.empty(), navigator.labelPoint(2.5));
  }

  @Test
  void labelPointFollowsTheGuideBackToTheTrail() {
    var navigator = new TestNavigator(straightPath(10, -1));
    navigator.player = new Vec3(2.5, 0, 6.5); // off to the side of the trail
    navigator.tick();
    assertEquals(Optional.empty(), navigator.labelPoint(2.5), "no guide drawn yet");

    // A guide straight back toward the trail, as the guide search would find.
    List<Step<Vec3, MinecraftStepPayload>> guide = new ArrayList<>();
    for (int z = 5; z >= 1; z--) {
      guide.add(
          new Step<>(new Vec3(2, 0, z), 1, 1, MinecraftStepPayload.of(MinecraftStepType.WALK)));
    }
    navigator.setGuidePath(new Path<>(new Vec3(2, 0, 6), guide));
    navigator.tick();

    Vec3 point = navigator.labelPoint(2.5).orElseThrow();
    assertEquals(2.5, point.minus(navigator.player).length(), 1e-9);
    assertEquals(2.5, point.x(), 1e-9, "on the guide");
    assertTrue(point.z() < 4.5 && point.z() > 3.5, "toward the trail: " + point);
  }

  @Test
  void noLabelPointWhileLabelsAreOff() {
    var navigator = new TestNavigator(straightPath(10, -1));
    navigator.showLabel.set(false);
    navigator.tick();

    assertEquals(Optional.empty(), navigator.labelPoint(2.5));
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

  @Test
  void noLabelPointPastAnActionStep() {
    var navigator = new TestNavigator(straightPath(10, 1)); // the second step opens a door
    navigator.tick();

    assertEquals(Optional.empty(), navigator.labelPoint(2.5));
  }

  @Test
  void noLabelPointNearTheEnd() {
    var navigator = new TestNavigator(straightPath(2, -1));
    navigator.tick();

    assertEquals(Optional.empty(), navigator.labelPoint(2.5));
  }

  @Test
  void particlesFillEachSegmentEvenly() {
    // Dense enough that a gap is no accident: each 0.2-block slice of trail expects 20 particles.
    // Were a segment's particles in a tick scattered around one point, as they once were, slices
    // more than half a block from it would stay empty.
    var navigator = new TestNavigator(straightPath(10, -1), 100);
    navigator.tick();

    boolean[] filled = new boolean[30]; // 0.2-block slices from x = 3 to x = 9
    for (Vec3 particle : navigator.particles) {
      int slice = (int) Math.floor((particle.x() - 3) / 0.2);
      if (slice >= 0 && slice < filled.length) {
        filled[slice] = true;
      }
    }
    for (int slice = 0; slice < filled.length; slice++) {
      assertTrue(filled[slice], "no particle near x = " + (3 + slice * 0.2));
    }
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
    final AtomicBoolean showLabel;
    final List<Vec3> particles = new ArrayList<>();

    TestNavigator(Path<Vec3, MinecraftStepPayload> path) {
      this(path, TrailLabels.none());
    }

    TestNavigator(Path<Vec3, MinecraftStepPayload> path, double density) {
      this(path, density, new AtomicBoolean(true), TrailLabels.none());
    }

    TestNavigator(Path<Vec3, MinecraftStepPayload> path, TrailLabels<Vec3> labels) {
      this(path, 1, new AtomicBoolean(true), labels);
    }

    private TestNavigator(
        Path<Vec3, MinecraftStepPayload> path,
        double density,
        AtomicBoolean showLabel,
        TrailLabels<Vec3> labels) {
      super(
          64,
          density,
          0,
          new Messages(Locale.ROOT, false, new SilentLogger()),
          Locale.ROOT,
          labels,
          showLabel::get);
      this.showLabel = showLabel;
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
    protected void spawnHighlightParticle(double x, double y, double z) {}

    @Override
    protected TrailSmoother.BlockProbe blockProbe() {
      return (x, y, z) -> y < 0 ? TrailBlock.SOLID : TrailBlock.OPEN;
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
