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
 * <p>Keyed by world, then by name, and <b>rewritten in that order every time</b>. Scenarios are
 * grouped by the terrain they touch because that is how they are captured and thought about, and
 * because a name then only has to be unique within its world. Sorting on every write means marking
 * a position in-game produces a one-entry diff instead of a reshuffle.
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
      # Benchmark scenarios, grouped by the world they touch and then by name.
      # Positions are written by /copier mark; everything else is edited by hand.
      """;

  // A literal newline rather than the platform separator: this file is committed, and a CRLF
  // rewrite on one developer's machine would churn every line of the diff.
  private static final String NL = "\n";

  private ScenarioFile() {}

  /**
   * Records a position for a scenario, creating the entry if it is new.
   *
   * @param file the scenarios file
   * @param name the scenario's name within its world
   * @param which {@code origin} or {@code dest}
   * @param world the world key
   * @param x the block X
   * @param y the block Y
   * @param z the block Z
   * @return whether the entry was newly created
   * @throws IOException if the file cannot be read or written
   */
  static boolean mark(Path file, String name, String which, String world, int x, int y, int z)
      throws IOException {
    Map<String, Map<String, List<String>>> worlds = read(file);
    Map<String, List<String>> entries = worlds.computeIfAbsent(world, key -> new LinkedHashMap<>());
    boolean created = !entries.containsKey(name);
    List<String> body = entries.computeIfAbsent(name, key -> new ArrayList<>());

    if (created) {
      body.add("    capture: " + name);
      body.add("    tier: LOCAL");
      body.add("    io: zero");
    }
    replaceOrAppend(
        body,
        "    " + (which.equals("origin") ? "origin" : "destination") + ":",
        "    %s: { x: %d, y: %d, z: %d }"
            .formatted(which.equals("origin") ? "origin" : "destination", x, y, z));

    write(file, worlds);
    return created;
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
  private static Map<String, Map<String, List<String>>> read(Path file) throws IOException {
    Map<String, Map<String, List<String>>> worlds = new LinkedHashMap<>();
    if (!Files.isRegularFile(file)) {
      return worlds;
    }
    Map<String, List<String>> entries = null;
    List<String> current = null;
    for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
      if (line.isBlank() || line.stripLeading().startsWith("#")) {
        continue;
      }
      String trimmed = line.stripTrailing();
      int indent = line.length() - line.stripLeading().length();
      if (indent == 0 && trimmed.endsWith(":")) {
        // Only the trailing colon comes off: a world key is namespaced, so the colon inside
        // "minecraft:overworld" is part of the name.
        entries =
            worlds.computeIfAbsent(withoutTrailingColon(trimmed), key -> new LinkedHashMap<>());
        current = null;
      } else if (indent == 2 && trimmed.endsWith(":") && entries != null) {
        current = new ArrayList<>();
        entries.put(withoutTrailingColon(trimmed.strip()), current);
      } else if (current != null) {
        current.add(line);
      }
    }
    return worlds;
  }

  private static String withoutTrailingColon(String key) {
    return key.substring(0, key.length() - 1);
  }

  private static void write(Path file, Map<String, Map<String, List<String>>> worlds)
      throws IOException {
    StringBuilder text = new StringBuilder(HEADER);
    for (Map.Entry<String, Map<String, List<String>>> world : new TreeMap<>(worlds).entrySet()) {
      text.append(NL).append(world.getKey()).append(":").append(NL);
      for (Map.Entry<String, List<String>> entry : new TreeMap<>(world.getValue()).entrySet()) {
        text.append("  ").append(entry.getKey()).append(":").append(NL);
        for (String line : entry.getValue()) {
          text.append(line).append(NL);
        }
        text.append(NL);
      }
    }
    Path parent = file.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Files.writeString(file, text.toString().stripTrailing() + NL, StandardCharsets.UTF_8);
  }
}
