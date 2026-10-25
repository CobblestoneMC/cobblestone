/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.bench;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * The verify suite: every route a scenario lists under {@code verify}, each checked in a JVM of its
 * own, several at once.
 *
 * <p><b>A route in the suite promises to fit its limits.</b> Listing a loadout under a scenario's
 * {@code verify} says that verifying it completes within {@link #HEAP} and {@link #TIME_LIMIT}, and
 * the suite holds it to that: a route that runs out of memory or time fails the suite, the same as
 * any other broken expectation. A JVM per route is what makes the memory limit a limit: threads in
 * one JVM share one heap, and one route's Dijkstra could starve every other.
 *
 * <p><b>Half the machine.</b> Routes run as many at once as fit in half the memory free when the
 * suite starts and half the processors, and never fewer than one: a developer's machine should run
 * the suite in a reasonable time and still be usable while it does.
 */
final class VerifySuite {

  /** Each route's heap. */
  static final long HEAP = 2L << 30;

  /** Each route's time. */
  static final Duration TIME_LIMIT = Duration.ofMinutes(5);

  /** What a route's JVM holds beyond its heap: code, threads, metaspace. */
  private static final long OVERHEAD = 512L << 20;

  private VerifySuite() {}

  /** One route of the suite. */
  record Route(Scenario scenario, Loadout loadout) {
    String id() {
      return scenario.id() + "@" + loadout.name();
    }
  }

  /** What one route's JVM did. */
  private record Outcome(Route route, String output, List<Double> ratios, String failure) {}

  /**
   * Runs the suite and reports it.
   *
   * @param routes the routes, in the order to report them
   * @param corpusRoot the corpus
   * @param scenariosFile the scenarios file the routes came from
   * @return {@code true} if every route verified within its limits
   * @throws InterruptedException if interrupted while waiting for the routes
   */
  static boolean run(List<Route> routes, Path corpusRoot, Path scenariosFile)
      throws InterruptedException {
    int parallel = parallelism(availableMemory(), Runtime.getRuntime().availableProcessors());
    System.out.printf(
        Locale.ROOT,
        "Verify suite: %d routes, %d at a time, each within %d GB and %d minutes%n",
        routes.size(),
        parallel,
        HEAP >> 30,
        TIME_LIMIT.toMinutes());
    ExecutorService pool = Executors.newFixedThreadPool(parallel);
    try {
      List<Future<Outcome>> running = new ArrayList<>();
      for (Route route : routes) {
        running.add(pool.submit(() -> verify(route, corpusRoot, scenariosFile)));
      }
      List<Double> ratios = new ArrayList<>();
      List<Outcome> failed = new ArrayList<>();
      // Reported in suite order as each lands, so the output reads the same however it ran.
      for (Future<Outcome> future : running) {
        Outcome outcome;
        try {
          outcome = future.get();
        } catch (ExecutionException e) {
          throw new IllegalStateException(e.getCause());
        }
        System.out.print(outcome.output());
        ratios.addAll(outcome.ratios());
        if (outcome.failure() != null) {
          failed.add(outcome);
          System.out.printf(Locale.ROOT, "  FAILED: %s%n", outcome.failure());
        }
      }
      Verify.aggregate(ratios);
      if (!failed.isEmpty()) {
        System.out.printf(
            Locale.ROOT,
            "%n%d of %d routes did not verify within their limits. Fix them, or take them out of"
                + " their scenario's verify list:%n",
            failed.size(),
            routes.size());
        for (Outcome outcome : failed) {
          System.out.printf(Locale.ROOT, "  %s: %s%n", outcome.route().id(), outcome.failure());
        }
      }
      return failed.isEmpty();
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * Returns how many routes to run at once: as many as fit in half the free memory and half the
   * processors, and at least one.
   *
   * @param freeMemory the memory free, in bytes
   * @param processors the processors
   * @return the routes to run at once
   */
  static int parallelism(long freeMemory, int processors) {
    long byMemory = (freeMemory / 2) / (HEAP + OVERHEAD);
    long byProcessors = processors / 2;
    return (int) Math.max(1, Math.min(byMemory, byProcessors));
  }

  /** Verifies one route in a JVM of its own, held to the suite's limits. */
  private static Outcome verify(Route route, Path corpusRoot, Path scenariosFile)
      throws IOException, InterruptedException {
    List<String> command = new ArrayList<>();
    command.add(ProcessHandle.current().info().command().orElse("java"));
    command.add("-Xmx" + (HEAP >> 20) + "m");
    command.add("-XX:+ExitOnOutOfMemoryError");
    command.add("-D" + Verify.RATIOS_PROPERTY + "=true");
    // The tuning knobs a run was started with apply to its routes too.
    System.getProperties().stringPropertyNames().stream()
        .filter(name -> name.startsWith("regions."))
        .forEach(name -> command.add("-D" + name + "=" + System.getProperty(name)));
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add(BenchMain.class.getName());
    command.addAll(
        List.of(
            "verify",
            "--scenario",
            route.scenario().id(),
            "--agent",
            route.loadout().name(),
            "--corpus",
            corpusRoot.toString(),
            "--scenarios",
            scenariosFile.toString()));
    Path log = Files.createTempFile("verify-", ".log");
    try {
      Process process =
          new ProcessBuilder(command)
              .redirectErrorStream(true)
              .redirectOutput(log.toFile())
              .start();
      long started = System.nanoTime();
      boolean finished = process.waitFor(TIME_LIMIT.toMillis(), TimeUnit.MILLISECONDS);
      if (!finished) {
        process.destroyForcibly().waitFor();
      }
      long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started);
      List<Double> ratios = new ArrayList<>();
      StringBuilder output = new StringBuilder();
      boolean outOfMemory = false;
      for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
        if (line.startsWith(Verify.RATIOS_PREFIX)) {
          for (String ratio : line.substring(Verify.RATIOS_PREFIX.length()).trim().split(" ")) {
            if (!ratio.isEmpty()) {
              ratios.add(Double.parseDouble(ratio));
            }
          }
        } else {
          outOfMemory |= line.contains("OutOfMemoryError");
          output.append(line).append(System.lineSeparator());
        }
      }
      String failure = null;
      if (!finished) {
        failure = "took longer than " + TIME_LIMIT.toMinutes() + " minutes";
      } else if (outOfMemory) {
        failure = "ran out of memory at " + (HEAP >> 30) + " GB";
      } else if (process.exitValue() != 0) {
        failure = "exited with " + process.exitValue();
      }
      output.append(String.format(Locale.ROOT, "  (%d s)%n", seconds));
      return new Outcome(route, output.toString(), failure == null ? ratios : List.of(), failure);
    } finally {
      Files.deleteIfExists(log);
    }
  }

  /**
   * Returns the memory free for new processes: Linux's own estimate where it gives one, which
   * counts the page cache it would give back, and otherwise what the JVM reports free.
   */
  private static long availableMemory() {
    try {
      for (String line : Files.readAllLines(Path.of("/proc/meminfo"))) {
        if (line.startsWith("MemAvailable:")) {
          return Long.parseLong(line.replaceAll("[^0-9]", "")) * 1024;
        }
      }
    } catch (IOException | RuntimeException notLinux) {
      // Not Linux, or not readable: the JVM's own figure below.
    }
    var os = ManagementFactory.getOperatingSystemMXBean();
    if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
      return sun.getFreeMemorySize();
    }
    return HEAP + OVERHEAD; // unknown: one at a time
  }
}
