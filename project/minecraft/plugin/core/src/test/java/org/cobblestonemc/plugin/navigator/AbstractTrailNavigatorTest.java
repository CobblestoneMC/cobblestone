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
import net.kyori.adventure.audience.Audience;
import org.cobblestonemc.CobblestoneLogger;
import org.cobblestonemc.api.Path;
import org.cobblestonemc.api.Step;
import org.cobblestonemc.minecraft.api.MinecraftStepPayload;
import org.cobblestonemc.minecraft.api.MinecraftStepType;
import org.cobblestonemc.plugin.message.Messages;
import org.junit.jupiter.api.Test;

/** How {@link AbstractTrailNavigator} decides which trail nodes the smoother may move. */
class AbstractTrailNavigatorTest {

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

  /** A navigator over a flat floor at y = -1, in one world, with the player at the origin. */
  private static final class TestNavigator extends AbstractTrailNavigator<Vec3> {

    TestNavigator(Path<Vec3, MinecraftStepPayload> path) {
      super(64, 1, 0, new Messages(Locale.ROOT, false, new SilentLogger()), Locale.ROOT);
      setPath(path);
    }

    @Override
    protected boolean playerOnline() {
      return true;
    }

    @Override
    protected Vec3 playerPoint() {
      return new Vec3(0.5, 0, 0.5);
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
        double x, double y, double z, double vx, double vy, double vz) {}

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
