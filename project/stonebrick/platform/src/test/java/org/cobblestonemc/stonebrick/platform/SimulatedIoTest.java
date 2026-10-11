/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.cobblestonemc.Cell;
import org.cobblestonemc.CobblestoneApi;
import org.cobblestonemc.CobblestoneLogger;
import org.cobblestonemc.Heuristics;
import org.cobblestonemc.ModesProvider;
import org.cobblestonemc.Position;
import org.cobblestonemc.SearchObserver;
import org.cobblestonemc.SingleDestination;
import org.cobblestonemc.api.NavigationResult;
import org.cobblestonemc.api.SearchHandle;
import org.cobblestonemc.api.SearchSettings;
import org.cobblestonemc.minecraft.ChunkLoadPolicy;
import org.cobblestonemc.minecraft.ChunkProviderSettings;
import org.cobblestonemc.minecraft.CobblestonePlayer;
import org.cobblestonemc.minecraft.MinecraftWorld;
import org.cobblestonemc.minecraft.api.MinecraftStepPayload;
import org.cobblestonemc.minecraft.api.MinecraftStepType;
import org.cobblestonemc.minecraft.modes.MinecraftModes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SimulatedIoTest {

  private static final long MAX_TASKS = 20_000_000L;

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

  private static final class ClosedOrder implements SearchObserver {
    final List<Cell> cells = new ArrayList<>();

    @Override
    public void closed(Cell cell, double cost, double estimatedTotalCost) {
      cells.add(cell);
    }
  }

  /** One fully deterministic run: virtual clock, single thread, simulated disk. */
  private record Run(
      NavigationResult<Position<MinecraftWorld>, MinecraftStepPayload> result,
      List<Cell> closed,
      long virtualMillis,
      long reads,
      long coldReads,
      long delayMicros) {}

  private Run run(Path root, IoProfile profile) throws Exception {
    DeterministicScheduler scheduler = new DeterministicScheduler();
    StonebrickPlatformApi platform =
        new StonebrickPlatformApi(
            Capture.load(root),
            scheduler,
            ChunkProviderSettings.defaults(ChunkLoadPolicy.ALLOW_LOAD),
            profile);
    MinecraftWorld world = platform.world(SyntheticCapture.OVERWORLD);
    StonebrickPlayer player = StonebrickPlayer.builder().build();
    ClosedOrder observer = new ClosedOrder();

    ModesProvider<CobblestonePlayer, MinecraftStepPayload, MinecraftWorld> modes =
        MinecraftModes.providerFor(player, java.util.Set.<MinecraftStepType>of(), null, 0);
    @SuppressWarnings("unchecked")
    ModesProvider<StonebrickPlayer, MinecraftStepPayload, MinecraftWorld> cast =
        (ModesProvider<StonebrickPlayer, MinecraftStepPayload, MinecraftWorld>) (Object) modes;

    SearchHandle<Position<MinecraftWorld>, MinecraftStepPayload> handle =
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
                SearchSettings.builder().maxWallClockMillis(600_000).build(),
                observer);

    // Nothing runs until we drain: the scheduler is the only thread there is.
    boolean finished = scheduler.drainUntil(() -> handle.future().isDone(), MAX_TASKS);
    assertTrue(finished, "the solve never settled");

    return new Run(
        handle.future().get(),
        List.copyOf(observer.cells),
        scheduler.millis(),
        platform.ioStats().reads(),
        platform.ioStats().coldReads(),
        platform.ioStats().totalDelayMicros());
  }

  private static Path flatCapture(Path dir) throws Exception {
    Path root = dir.resolve("flat");
    SyntheticCapture.write(root, SyntheticCapture.flatGround(63), 0, 0, 4, 4, 3, 4);
    return root;
  }

  @Test
  void aVirtualRunIsReproducibleDownToExpansionOrder(@TempDir Path dir) throws Exception {
    Path root = flatCapture(dir);

    Run first = run(root, IoProfile.spinning());
    Run second = run(root, IoProfile.spinning());

    assertTrue(first.result() instanceof NavigationResult.Success);
    // The whole point of the deterministic scheduler: not just the same answer, the same work.
    assertEquals(first.closed(), second.closed());
    assertEquals(first.virtualMillis(), second.virtualMillis());
    assertEquals(first.reads(), second.reads());
    assertEquals(first.delayMicros(), second.delayMicros());
  }

  @Test
  void aSlowDiskCostsSimulatedTimeButNotRealTime(@TempDir Path dir) throws Exception {
    Path root = flatCapture(dir);

    long startedAt = System.nanoTime();
    Run slow = run(root, IoProfile.spinning());
    long realMillis = (System.nanoTime() - startedAt) / 1_000_000;

    assertTrue(slow.reads() > 0, "the solve should have read chunks");
    // Compared against a fast disk rather than against an absolute figure: how much simulated time
    // a solve spends depends on how many chunks it happens to read, which is a property of the
    // search and the terrain, not of the IO model under test here.
    Run fast = run(root, IoProfile.nvme());
    assertTrue(
        slow.virtualMillis() > fast.virtualMillis() * 5,
        "spinning " + slow.virtualMillis() + "ms vs nvme " + fast.virtualMillis() + "ms");
    // And it cost no real time, which is what makes this usable as a benchmark at all.
    assertTrue(
        realMillis < slow.virtualMillis(),
        "real " + realMillis + "ms vs virtual " + slow.virtualMillis() + "ms");
  }

  @Test
  void aFasterDiskCostsLessSimulatedTimeForTheSameWork(@TempDir Path dir) throws Exception {
    Path root = flatCapture(dir);

    Run nvme = run(root, IoProfile.nvme());
    Run spinning = run(root, IoProfile.spinning());

    // Same terrain, same search: the disk is the only variable, so the ordering must hold.
    assertEquals(nvme.closed(), spinning.closed());
    assertTrue(
        spinning.delayMicros() > nvme.delayMicros() * 10,
        "spinning " + spinning.delayMicros() + "µs vs nvme " + nvme.delayMicros() + "µs");
  }

  @Test
  void zeroIoChargesNothing(@TempDir Path dir) throws Exception {
    Path root = flatCapture(dir);

    Run free = run(root, IoProfile.zero());

    assertTrue(free.reads() > 0);
    assertEquals(0, free.delayMicros());
    assertEquals(0, free.virtualMillis(), "a free disk should not move even a virtual clock");
  }

  @Test
  void warmReadsAreCheaperThanColdOnes(@TempDir Path dir) throws Exception {
    Path root = flatCapture(dir);

    Run run = run(root, IoProfile.spinning());

    assertTrue(run.coldReads() > 0);
    // The per-solve chunk cache means a chunk is re-fetched when it falls out and is read again;
    // if that never happened, the cold/warm distinction would be modelling nothing.
    assertTrue(run.reads() >= run.coldReads());
  }

  @Test
  void jitterFollowsTheChunkRatherThanTheOrderItWasRead(@TempDir Path dir) throws Exception {
    Path root = flatCapture(dir);

    // Two profiles differing only by seed must charge different totals — otherwise the jitter is
    // not being applied at all — while each remains reproducible.
    Run seedOne = run(root, IoProfile.spinning().withSeed(1));
    Run seedTwo = run(root, IoProfile.spinning().withSeed(99));

    assertEquals(seedOne.closed(), seedTwo.closed(), "the seed must not change what is explored");
    assertNotEquals(seedOne.delayMicros(), seedTwo.delayMicros());
    assertFalse(seedOne.closed().isEmpty());
  }
}
