/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.bench;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;
import org.yaml.snakeyaml.Yaml;

/**
 * The accepted numbers for a scenario, stored in version control beside the code.
 *
 * <p>There is no optimal path to measure against — a true Dijkstra over these distances is the very
 * problem the search exists to avoid — so the reference is our own last accepted result. That makes
 * the baseline a record of a decision rather than a fact about the world, which is exactly why it
 * lives in git: changing it is a diff somebody reviews.
 *
 * <p>Written as JSON with a fixed key order, so regenerating one produces a readable diff rather
 * than a reshuffle. Read back with the YAML parser, since JSON is valid YAML.
 */
public final class Baselines {

  /** Default tolerance on path cost: tight, because an unchanged algorithm should not move it. */
  public static final double COST_TOLERANCE = 0.005;

  /** Default tolerance on counts, absorbing nothing but genuine drift. */
  public static final double COUNT_TOLERANCE = 0.02;

  private Baselines() {}

  /**
   * One scenario's accepted numbers.
   *
   * @param scenario the scenario id
   * @param acceptedAt when it was accepted
   * @param commit the commit it was accepted at
   * @param outcome the accepted outcome
   * @param deterministic the accepted gated metrics
   * @param advisory the recorded ungated metrics
   */
  public record Baseline(
      String scenario,
      String acceptedAt,
      String commit,
      String outcome,
      Map<String, Number> deterministic,
      Map<String, Number> advisory) {}

  /**
   * Returns the file a scenario's baseline lives in.
   *
   * @param dir the baselines directory
   * @param scenarioId the scenario id
   * @return the file path
   */
  public static Path fileFor(Path dir, String scenarioId) {
    return dir.resolve(scenarioId + ".json");
  }

  /**
   * Reads a scenario's baseline, or returns {@code null} if it has none yet.
   *
   * @param dir the baselines directory
   * @param scenarioId the scenario id
   * @return the baseline, or {@code null}
   * @throws IOException if the file exists but cannot be read
   */
  public static @Nullable Baseline read(Path dir, String scenarioId) throws IOException {
    Path file = fileFor(dir, scenarioId);
    if (!Files.exists(file)) {
      return null;
    }
    try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      Map<String, Object> root = new Yaml().load(reader);
      return new Baseline(
          String.valueOf(root.getOrDefault("scenario", scenarioId)),
          String.valueOf(root.getOrDefault("acceptedAt", "")),
          String.valueOf(root.getOrDefault("commit", "")),
          String.valueOf(root.getOrDefault("outcome", "")),
          numbers(root.get("deterministic")),
          numbers(root.get("advisory")));
    } catch (RuntimeException e) {
      throw new IOException(file + ": " + e.getMessage(), e);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Number> numbers(Object node) {
    Map<String, Number> values = new java.util.LinkedHashMap<>();
    if (node instanceof Map<?, ?> map) {
      for (Map.Entry<String, Object> entry : ((Map<String, Object>) map).entrySet()) {
        if (entry.getValue() instanceof Number number) {
          values.put(entry.getKey(), number);
        }
      }
    }
    return values;
  }

  /**
   * Writes a run as the new accepted baseline.
   *
   * @param dir the baselines directory
   * @param result the run to accept
   * @param commit the commit being accepted at
   * @throws IOException if the file cannot be written
   */
  public static void accept(Path dir, RunResult result, String commit) throws IOException {
    if (!result.isValid()) {
      throw new IOException(
          result.scenario()
              + ": refusing to accept a run that read outside its capture:\n"
              + result.missingCaptureReport());
    }
    Files.createDirectories(dir);
    StringBuilder json = new StringBuilder();
    json.append("{\n");
    json.append("  \"scenario\": \"").append(result.scenario()).append("\",\n");
    json.append("  \"acceptedAt\": \"").append(java.time.LocalDate.now()).append("\",\n");
    json.append("  \"commit\": \"").append(commit).append("\",\n");
    json.append("  \"outcome\": \"").append(result.outcome()).append("\",\n");
    json.append("  \"deterministic\": {\n");
    appendNumbers(json, result.deterministic());
    json.append("  },\n");
    json.append("  \"advisory\": {\n");
    appendNumbers(json, result.advisory());
    json.append("  }\n");
    json.append("}\n");
    Files.writeString(fileFor(dir, result.scenario()), json.toString(), StandardCharsets.UTF_8);
  }

  private static void appendNumbers(StringBuilder json, Map<String, Number> values) {
    List<Map.Entry<String, Number>> entries = new ArrayList<>(values.entrySet());
    for (int i = 0; i < entries.size(); i++) {
      Map.Entry<String, Number> entry = entries.get(i);
      json.append("    \"").append(entry.getKey()).append("\": ");
      Number value = entry.getValue();
      if (value instanceof Double || value instanceof Float) {
        json.append(String.format(java.util.Locale.ROOT, "%.4f", value.doubleValue()));
      } else {
        json.append(value.longValue());
      }
      json.append(i == entries.size() - 1 ? "\n" : ",\n");
    }
  }

  /**
   * Returns the tolerance for a metric.
   *
   * <p>Cost is held tighter than counts because it is the number a change is most likely to move
   * quietly and most likely to matter: a search that expands two percent more nodes is noise, and
   * one that returns a two percent worse path is a different answer.
   *
   * @param metric the metric name
   * @return the fractional tolerance
   */
  public static double toleranceFor(String metric) {
    return metric.startsWith("path") ? COST_TOLERANCE : COUNT_TOLERANCE;
  }
}
