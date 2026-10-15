/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.utility;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.cobblestonemc.stonebrick.bench.Loadout;
import org.cobblestonemc.stonebrick.bench.NeededChunks;
import org.cobblestonemc.stonebrick.bench.Scenario;
import org.cobblestonemc.stonebrick.bench.ScenarioCheck;
import org.cobblestonemc.stonebrick.format.CaptureDirectory;

/**
 * Runs {@code /stonebrick check}: captures a scenario's terrain from this server and runs the
 * bench's {@link ScenarioCheck} on it.
 *
 * <p>The bench runs on a thread of its own, never the server's: a Dijkstra run can take minutes.
 * Only the capture touches the world, and it goes through the same rate-limited job as {@code
 * /stonebrick copy}, so the server keeps ticking throughout. One check at a time.
 */
final class ScenarioChecker {

  private final Plugin plugin;
  private final UtilityState state;
  private final AtomicBoolean running = new AtomicBoolean();

  ScenarioChecker(Plugin plugin, UtilityState state) {
    this.plugin = plugin;
    this.state = state;
  }

  /**
   * Writes the bundled loadouts beside the scenarios, unless someone already has.
   *
   * @throws IOException if they cannot be written
   */
  void installLoadouts() throws IOException {
    Path file = state.outputRoot().resolve(Loadout.FILE_NAME);
    if (Files.exists(file)) {
      return;
    }
    Files.createDirectories(file.getParent());
    try (InputStream bundled = plugin.getResource(Loadout.FILE_NAME)) {
      if (bundled != null) {
        Files.copy(bundled, file);
      }
    }
  }

  /**
   * Starts checking a scenario.
   *
   * @param name the scenario's name, or its full id
   * @param generate whether chunks that do not exist yet may be generated
   * @param report where report lines go
   * @return whether a check started; if not, {@code report} has been told why
   */
  boolean start(String name, boolean generate, Consumer<String> report) {
    Scenario scenario;
    List<Loadout> loadouts;
    try {
      scenario = find(name);
      loadouts = Loadout.loadAll(state.outputRoot().resolve(Loadout.FILE_NAME));
    } catch (IOException e) {
      report.accept("Could not read the scenarios: " + e.getMessage());
      return false;
    }
    if (scenario == null) {
      report.accept(
          "No complete scenario '" + name + "'. Mark both its origin and its dest first.");
      return false;
    }
    if (!running.compareAndSet(false, true)) {
      report.accept("A check is already running.");
      return false;
    }
    Thread worker =
        new Thread(
            () -> {
              try {
                boolean usable =
                    ScenarioCheck.run(
                        state.outputRoot(),
                        scenario,
                        loadouts,
                        capsule -> capture(scenario, capsule, generate, report),
                        report);
                if (usable) {
                  report.accept(
                      "  To add it to the corpus, copy its entries from "
                          + state.outputRoot().resolve(ScenarioFile.FILE_NAME)
                          + " and needed-chunks.yml beside it into stonebrick/data.");
                }
              } catch (IOException | RuntimeException e) {
                report.accept("The check failed: " + e);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                report.accept("The check was interrupted.");
              } finally {
                running.set(false);
              }
            },
            "stonebrick-check");
    worker.setDaemon(true);
    worker.start();
    return true;
  }

  private Scenario find(String name) throws IOException {
    Path file = state.outputRoot().resolve(ScenarioFile.FILE_NAME);
    if (!Files.isRegularFile(file)) {
      return null;
    }
    for (Scenario scenario : Scenario.loadAll(file)) {
      if (scenario.id().equals(name) || scenario.id().endsWith("/" + name)) {
        return scenario;
      }
    }
    return null;
  }

  /** Captures the capsule's missing chunks through the server, and waits for them. */
  private void capture(
      Scenario scenario, NeededChunks capsule, boolean generate, Consumer<String> report)
      throws IOException, InterruptedException {
    CompletableFuture<CaptureJob> started = new CompletableFuture<>();
    Bukkit.getScheduler()
        .runTask(
            plugin,
            () -> {
              try {
                started.complete(job(scenario, capsule, generate, report));
              } catch (IOException | RuntimeException e) {
                started.completeExceptionally(e);
              }
            });
    try {
      CaptureJob job = started.get();
      if (job == null) {
        return; // nothing missing
      }
      job.done().get();
      if (job.failures() > 0) {
        report.accept(
            String.format(
                "  ! %,d chunks could not be captured%s.",
                job.failures(),
                generate
                    ? ""
                    : "; they may not be generated yet. Fly over them, or check with --generate"));
      }
    } catch (ExecutionException e) {
      throw new IOException(e.getCause().getMessage(), e.getCause());
    }
  }

  /** Starts a capture of whatever the capsule needs and the capture lacks; on the server thread. */
  private CaptureJob job(
      Scenario scenario, NeededChunks capsule, boolean generate, Consumer<String> report)
      throws IOException {
    NamespacedKey key = NamespacedKey.fromString(scenario.world());
    World world = key == null ? null : Bukkit.getWorld(key);
    if (world == null) {
      throw new IOException("no world '" + scenario.world() + "' on this server");
    }
    if (state.hasActiveJob()) {
      throw new IOException("a capture is already running: " + state.activeStatus());
    }
    CaptureDirectory capture = state.capture(scenario.capture());
    String worldKey = world.getKey().asString();
    int minX = (Math.min(capsule.from().x(), capsule.to().x()) >> 4) - capsule.radiusChunks();
    int maxX = (Math.max(capsule.from().x(), capsule.to().x()) >> 4) + capsule.radiusChunks();
    int minZ = (Math.min(capsule.from().z(), capsule.to().z()) >> 4) - capsule.radiusChunks();
    int maxZ = (Math.max(capsule.from().z(), capsule.to().z()) >> 4) + capsule.radiusChunks();
    // Decided once, up front: the job asks its filter again as it goes, and a filter that read
    // the disk would change its answer as the job wrote, skipping chunks it had counted.
    java.util.Set<Long> wanted = new java.util.HashSet<>();
    for (int x = minX; x <= maxX; x++) {
      for (int z = minZ; z <= maxZ; z++) {
        if (capsule.covers(x, z) && !Files.exists(capture.column(worldKey, x, z))) {
          wanted.add(((long) x << 32) | (z & 0xFFFFFFFFL));
        }
      }
    }
    CaptureJob.ChunkFilter missing =
        (x, z) -> wanted.contains(((long) x << 32) | (z & 0xFFFFFFFFL));
    CaptureJob job =
        new CaptureJob(
            plugin,
            world,
            capture,
            new VerticalMode.Full(),
            generate,
            minX,
            minZ,
            maxX,
            maxZ,
            missing,
            report);
    if (job.total() == 0 && Files.exists(capture.traitTable())) {
      return null;
    }
    state.start(job);
    return job;
  }
}
