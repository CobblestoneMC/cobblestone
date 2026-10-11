/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.cobblestonemc.Cell;
import org.cobblestonemc.CobblestoneLogger;
import org.cobblestonemc.Position;
import org.cobblestonemc.api.NavigationResult;
import org.cobblestonemc.api.SearchSettings;
import org.cobblestonemc.minecraft.ChunkLoadPolicy;
import org.cobblestonemc.minecraft.ChunkProviderSettings;
import org.cobblestonemc.minecraft.MinecraftWorld;
import org.cobblestonemc.minecraft.api.MinecraftStepPayload;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StonebrickPlatformTest {

  private StonebrickScheduler scheduler;

  @AfterEach
  void stopScheduler() {
    if (scheduler != null) {
      scheduler.close();
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

  private StonebrickPlatformApi platform(Path root) throws Exception {
    scheduler = new StonebrickScheduler(4);
    return new StonebrickPlatformApi(
        Capture.load(root), scheduler, ChunkProviderSettings.defaults(ChunkLoadPolicy.ALLOW_LOAD));
  }

  @Test
  void aSearchCrossesFlatCapturedGround(@TempDir Path dir) throws Exception {
    Path root = dir.resolve("flat");
    // Five chunks square of flat stone at y=63, captured as the two sections around the surface.
    SyntheticCapture.write(root, SyntheticCapture.flatGround(63), 0, 0, 4, 4, 3, 4);

    StonebrickPlatformApi platform = platform(root);
    MinecraftWorld world = platform.world(SyntheticCapture.OVERWORLD);
    assertNotNull(world, "the capture should expose the overworld");
    assertEquals(25, platform.capture().columnCount(SyntheticCapture.OVERWORLD));

    StonebrickSolve solve = new StonebrickSolve(platform, scheduler, new SilentLogger());
    NavigationResult<Position<MinecraftWorld>, MinecraftStepPayload> result =
        solve
            .run(
                StonebrickPlayer.builder().build(),
                new Position<>(new Cell(8, 64, 8), world),
                StonebrickSolve.at(world, new Cell(56, 64, 56)),
                SearchSettings.builder().maxWallClockMillis(30_000).build(),
                List.of(),
                List.of())
            .get(60, TimeUnit.SECONDS);

    var success =
        assertInstanceOf(
            NavigationResult.Success.class, result, "expected a path across open ground");
    assertFalse(success.path().steps().isEmpty());
    // Every block the search read was inside the capture, so the number means something.
    assertTrue(platform.missing().isEmpty(), platform.missing().report("flat"));
  }

  @Test
  void readingOutsideTheCaptureIsRecordedRatherThanSilentlyWalled(@TempDir Path dir)
      throws Exception {
    Path root = dir.resolve("narrow");
    // Two chunks of ground, and a destination well outside them. The search cannot get there, but
    // the point is not the failure — it is that the harness can say *why*.
    SyntheticCapture.write(root, SyntheticCapture.flatGround(63), 0, 0, 1, 1, 3, 4);

    StonebrickPlatformApi platform = platform(root);
    MinecraftWorld world = platform.world(SyntheticCapture.OVERWORLD);
    StonebrickSolve solve = new StonebrickSolve(platform, scheduler, new SilentLogger());

    solve
        .run(
            StonebrickPlayer.builder().build(),
            new Position<>(new Cell(8, 64, 8), world),
            StonebrickSolve.at(world, new Cell(200, 64, 200)),
            SearchSettings.builder().maxWallClockMillis(10_000).maxCellsVisited(20_000).build(),
            List.of(),
            List.of())
        .get(30, TimeUnit.SECONDS);

    assertFalse(platform.missing().isEmpty(), "reads past the capture edge must be recorded");
    String report = platform.missing().report("narrow");
    // The report has to be actionable on its own: a region, and the command that captures it.
    assertTrue(report.contains("/copier copy"), report);
    assertTrue(report.contains(SyntheticCapture.OVERWORLD), report);
    assertTrue(report.contains("degenerate"), report);
  }

  @Test
  void aCubeBelowTheCapturedBandIsRecordedToo(@TempDir Path dir) throws Exception {
    Path root = dir.resolve("shallow");
    // Ground at y=63 but only the two sections around it captured: anything that digs below y=48
    // has left the capture even though the column file exists.
    SyntheticCapture.write(root, SyntheticCapture.flatGround(63), 0, 0, 1, 1, 3, 4);

    StonebrickPlatformApi platform = platform(root);
    MinecraftWorld world = platform.world(SyntheticCapture.OVERWORLD);

    // Read straight through the floor of the captured band.
    var chunk = world.chunkAt(new Cell(8, 40, 8), new Cell(8, 40, 8));
    assertNotNull(chunk);

    world.blockAt(new Cell(8, 40, 8), new Cell(8, 40, 8)).toFuture().get();

    assertFalse(platform.missing().isEmpty());
    String report = platform.missing().report("shallow");
    // A read below the band wants a deeper capture, not a wider one — the command must say so.
    assertTrue(report.contains("--y"), report);
  }

  @Test
  void capturedTraitsSurviveTheRoundTrip(@TempDir Path dir) throws Exception {
    Path root = dir.resolve("traits");
    SyntheticCapture.write(root, SyntheticCapture.flatGround(63), 0, 0, 0, 0, 3, 4);

    StonebrickPlatformApi platform = platform(root);
    MinecraftWorld world = platform.world(SyntheticCapture.OVERWORLD);

    var stone = world.blockAt(new Cell(0, 60, 0), new Cell(0, 60, 0)).toFuture().get();
    var air = world.blockAt(new Cell(0, 70, 0), new Cell(0, 70, 0)).toFuture().get();

    assertTrue(stone.isSolidTop());
    assertFalse(stone.isPassable());
    assertEquals(1.5, stone.breakTimeSeconds());
    assertTrue(air.isPassable());
    assertFalse(air.isSolidTop());
  }

  @Test
  void aScopedWorldEqualsTheOneItCameFrom(@TempDir Path dir) throws Exception {
    Path root = dir.resolve("scoped");
    SyntheticCapture.write(root, SyntheticCapture.flatGround(63), 0, 0, 0, 0, 3, 4);

    StonebrickPlatformApi platform = platform(root);
    MinecraftWorld world = platform.world(SyntheticCapture.OVERWORLD);

    // Domains are map keys and participate in Position equality; a scoped view that compared
    // unequal would silently split every lookup keyed by domain.
    assertEquals(world, world.scopedForSolve());
    assertEquals(world.hashCode(), world.scopedForSolve().hashCode());
    assertInstanceOf(StonebrickWorld.class, world.scopedForSolve());
  }
}
