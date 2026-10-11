/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.cobblestonemc.Cell;
import org.cobblestonemc.CobblestoneApi;
import org.cobblestonemc.CobblestoneLogger;
import org.cobblestonemc.Heuristics;
import org.cobblestonemc.ModesProvider;
import org.cobblestonemc.Position;
import org.cobblestonemc.SearchObserver;
import org.cobblestonemc.SingleDestination;
import org.cobblestonemc.api.NavigationResult;
import org.cobblestonemc.api.SearchSettings;
import org.cobblestonemc.minecraft.ChunkLoadPolicy;
import org.cobblestonemc.minecraft.ChunkProviderSettings;
import org.cobblestonemc.minecraft.CobblestonePlayer;
import org.cobblestonemc.minecraft.MinecraftWorld;
import org.cobblestonemc.minecraft.api.MinecraftStepPayload;
import org.cobblestonemc.minecraft.api.MinecraftStepType;
import org.cobblestonemc.minecraft.modes.MinecraftModes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Guards the property the whole benchmark rests on: the same search over the same world must expand
 * the same cells in the same order every time. Without it, two runs of an unchanged algorithm
 * differ by more than most real improvements would, and no amount of averaging recovers the signal.
 *
 * <p><b>What this does and does not cover.</b> A capture is held in memory, so every chunk fetch
 * resolves immediately and no mode ever parks — which makes the solve effectively single-threaded
 * and removes the main source of run-to-run drift before the test starts. So this is a regression
 * guard against determinism being <em>lost</em> (a hash-ordered iteration leaking into expansion
 * order, a comparator becoming unstable), not evidence that a solve is reproducible under real
 * asynchronous IO. That needs a deterministic scheduler, and this test will not notice its absence.
 */
class DeterminismTest {

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

  /** Records the order cells were settled in. */
  private static final class ClosedOrder implements SearchObserver {
    final List<Cell> cells = new ArrayList<>();
    int opened;
    int parks;
    List<Cell> path = List.of();

    @Override
    public void opened(Cell cell, double cost, double estimatedTotalCost) {
      opened++;
    }

    @Override
    public void closed(Cell cell, double cost, double estimatedTotalCost) {
      cells.add(cell);
    }

    @Override
    public void parked() {
      parks++;
    }

    @Override
    public void solved(List<Cell> cells) {
      this.path = List.copyOf(cells);
    }
  }

  private ClosedOrder solve(Path root) throws Exception {
    scheduler = new StonebrickScheduler(4);
    StonebrickPlatformApi platform =
        new StonebrickPlatformApi(
            Capture.load(root),
            scheduler,
            ChunkProviderSettings.defaults(ChunkLoadPolicy.ALLOW_LOAD));
    MinecraftWorld world = platform.world(SyntheticCapture.OVERWORLD);
    StonebrickPlayer player = StonebrickPlayer.builder().build();
    ClosedOrder observer = new ClosedOrder();

    ModesProvider<CobblestonePlayer, MinecraftStepPayload, MinecraftWorld> modes =
        MinecraftModes.providerFor(player, java.util.Set.<MinecraftStepType>of(), null, 0);

    @SuppressWarnings("unchecked")
    ModesProvider<StonebrickPlayer, MinecraftStepPayload, MinecraftWorld> cast =
        (ModesProvider<StonebrickPlayer, MinecraftStepPayload, MinecraftWorld>) (Object) modes;

    NavigationResult<Position<MinecraftWorld>, MinecraftStepPayload> result =
        CobblestoneApi.load()
            .navigate(
                new SilentLogger(),
                scheduler,
                player,
                new Position<>(new Cell(8, 64, 8), world),
                new SingleDestination<>(StonebrickSolve.at(world, new Cell(56, 64, 56))),
                cast,
                List.of(),
                List.of(),
                Heuristics.runningAverage(
                    MinecraftModes.cheapestCostPerBlock(
                        player, java.util.Set.<MinecraftStepType>of())),
                SearchSettings.builder().maxWallClockMillis(30_000).build(),
                observer)
            .future()
            .get(60, TimeUnit.SECONDS);

    assertTrue(result instanceof NavigationResult.Success, "expected a path");
    assertTrue(platform.missing().isEmpty(), platform.missing().report("flat"));
    scheduler.close();
    scheduler = null;
    return observer;
  }

  @Test
  void twoRunsExpandTheSameCellsInTheSameOrder(@TempDir Path dir) throws Exception {
    // Passes with or without the open set's insertion-order tie-break, because with immediate
    // fetches the heap is already fed the same operations in the same order. The tie-break earns
    // its keep against a JDK changing PriorityQueue's unspecified order among equals, which no
    // test here can provoke.
    Path root = dir.resolve("flat");
    SyntheticCapture.write(root, SyntheticCapture.flatGround(63), 0, 0, 4, 4, 3, 4);

    ClosedOrder first = solve(root);
    ClosedOrder second = solve(root);

    assertFalse(first.cells.isEmpty(), "the observer should have seen expansions");
    // Not just the same count — the same cells, in the same order. A count-only assertion would
    // pass for two searches that explored the same amount of different ground.
    assertEquals(first.cells, second.cells);
    assertEquals(first.opened, second.opened);
    assertEquals(first.path, second.path);
  }

  @Test
  void theObserverSeesAPathThatStartsAtTheOrigin(@TempDir Path dir) throws Exception {
    Path root = dir.resolve("flat");
    SyntheticCapture.write(root, SyntheticCapture.flatGround(63), 0, 0, 4, 4, 3, 4);

    ClosedOrder observed = solve(root);

    assertFalse(observed.path.isEmpty());
    assertEquals(new Cell(8, 64, 8), observed.path.get(0), "the path must begin where we did");
    assertTrue(observed.opened >= observed.cells.size(), "everything closed was opened first");
  }

  @Test
  void theDefaultObserverIsShared() {
    // Identity matters: the search tests `observer == none()` in places, and a factory handing back
    // a new instance each call would make that always false while looking correct.
    assertSame(SearchObserver.none(), SearchObserver.none());
  }
}
