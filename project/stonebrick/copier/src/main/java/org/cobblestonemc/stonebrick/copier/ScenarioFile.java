/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.copier;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The corpus's single {@code scenarios.yml}, read and rewritten by {@code /copier mark}.
 *
 * <p>One file, keyed by scenario id, <b>rewritten in id order every time</b>. Marking a position
 * in-game then produces a one-entry diff instead of a reshuffle, which is the difference between a
 * file you can review and one you skim past.
 *
 * <p>A mark updates only the position it names and leaves everything else in the entry alone — the
 * agent, the settings, the tier — because those are edited by hand and would be infuriating to lose
 * to a mistyped command.
 *
 * <p>Written by hand rather than through a YAML library: the schema is ours, the output has to be
 * byte-stable, and a serializer's idea of formatting is one more thing that could change under us
 * and churn the file.
 */
final class ScenarioFile {

  /** The file's name inside a corpus. */
  static final String FILE_NAME = "scenarios.yml";

  private static final String HEADER =
      """
      # Benchmark scenarios, one per id, kept in alphabetical order.
      # Positions are written by /copier mark; everything else is edited by hand.
      """;

  private ScenarioFile() {}

  /**
   * Records a position for a scenario, creating the entry if it is new.
   *
   * @param file the scenarios file
   * @param id the scenario id
   * @param which {@code origin} or {@code dest}
   * @param world the world key
   * @param x the block X
   * @param y the block Y
   * @param z the block Z
   * @return whether the entry was newly created
   * @throws IOException if the file cannot be read or written
   */
  static boolean mark(Path file, String id, String which, String world, int x, int y, int z)
      throws IOException {
    Map<String, List<String>> entries = read(file);
    boolean created = !entries.containsKey(id);
    List<String> body = entries.computeIfAbsent(id, key -> new ArrayList<>());

    if (created) {
      body.add("  capture: " + id);
      body.add("  world: " + world);
      body.add("  tier: LOCAL");
      body.add("  io: zero");
    }
    setWorld(body, world);
    setPosition(body, which.equals("origin") ? "origin" : "destination", x, y, z);

    write(file, entries);
    return created;
  }

  private static void setWorld(List<String> body, String world) {
    replaceOrAppend(body, "  world:", "  world: " + world);
  }

  private static void setPosition(List<String> body, String key, int x, int y, int z) {
    replaceOrAppend(
        body, "  " + key + ":", "  %s: { x: %d, y: %d, z: %d }".formatted(key, x, y, z));
  }

  private static void replaceOrAppend(List<String> body, String prefix, String line) {
    for (int i = 0; i < body.size(); i++) {
      if (body.get(i).startsWith(prefix)) {
        body.set(i, line);
        return;
      }
    }
    body.add(line);
  }

  /**
   * Reads the file as raw lines per entry.
   *
   * <p>Lines rather than parsed values, so that hand-written fields this class does not understand
   * — comments included — survive a mark untouched. A round-trip through a model would quietly drop
   * everything the model has no field for.
   */
  private static Map<String, List<String>> read(Path file) throws IOException {
    Map<String, List<String>> entries = new LinkedHashMap<>();
    if (!Files.isRegularFile(file)) {
      return entries;
    }
    List<String> current = null;
    for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
      if (line.isBlank() || line.startsWith("#")) {
        continue;
      }
      if (!Character.isWhitespace(line.charAt(0)) && line.endsWith(":")) {
        current = new ArrayList<>();
        entries.put(line.substring(0, line.length() - 1).trim(), current);
      } else if (current != null) {
        current.add(line);
      }
    }
    return entries;
  }

  private static void write(Path file, Map<String, List<String>> entries) throws IOException {
    StringBuilder text = new StringBuilder(HEADER);
    for (Map.Entry<String, List<String>> entry : new TreeMap<>(entries).entrySet()) {
      text.append('\n').append(entry.getKey()).append(":\n");
      for (String line : entry.getValue()) {
        text.append(line).append('\n');
      }
    }
    Path parent = file.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Files.writeString(file, text.toString(), StandardCharsets.UTF_8);
  }
}
