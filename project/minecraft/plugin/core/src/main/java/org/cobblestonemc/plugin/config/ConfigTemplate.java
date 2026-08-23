/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Renders the commented {@code config.yml} template from a {@link ConfigManager}'s registry.
 *
 * <p>The registry is the single source of truth: defaults, documentation, and the values a key
 * accepts all come from the {@link ConfigKey#comment() registrations}, so the file an admin gets
 * cannot drift from the values the plugin actually uses. It also means each platform emits its own
 * file — keys a platform never registers simply do not appear, and a key whose prose or permitted
 * values differ per platform documents itself correctly on each.
 */
final class ConfigTemplate {

  private static final int INDENT = 2;

  private ConfigTemplate() {}

  /**
   * Renders the full template.
   *
   * @param header the banner comment lines for the top of the file (without leading {@code #})
   * @param sectionComments documentation for intermediate sections, by period-delimited path
   * @param keys the registered keys, in registration order
   * @return the YAML text
   */
  static String render(
      List<String> header,
      Map<String, List<String>> sectionComments,
      Collection<ConfigKey<?>> keys) {
    StringBuilder out = new StringBuilder();
    for (String line : header) {
      appendComment(out, 0, line);
    }
    Node root = new Node(null);
    for (ConfigKey<?> key : keys) {
      root.insert(key, key.path().split("\\."), 0);
    }
    emit(out, root, sectionComments, 0, header.isEmpty());
    return out.toString();
  }

  /** Emits one node's children; {@code atStart} suppresses the leading blank line. */
  private static void emit(
      StringBuilder out,
      Node node,
      Map<String, List<String>> sectionComments,
      int depth,
      boolean atStart) {
    boolean first = atStart;
    for (Map.Entry<String, Node> entry : node.children.entrySet()) {
      Node child = entry.getValue();
      if (child.key != null) {
        emitKey(out, child.key, depth);
        first = false;
        continue;
      }
      if (!first) {
        out.append(System.lineSeparator());
      }
      first = false;
      for (String line : sectionComments.getOrDefault(child.path, List.of())) {
        appendComment(out, depth, line);
      }
      indent(out, depth).append(entry.getKey()).append(':').append(System.lineSeparator());
      emit(out, child, sectionComments, depth + 1, true);
    }
  }

  private static void emitKey(StringBuilder out, ConfigKey<?> key, int depth) {
    for (String line : key.comment()) {
      appendComment(out, depth, line);
    }
    if (!key.permitted().isEmpty()) {
      List<String> names = new ArrayList<>(key.permitted().size());
      for (Object value : key.permitted()) {
        names.add(scalar(value));
      }
      appendComment(out, depth, "Options: " + String.join(", ", names) + ".");
    }
    if (!key.mutable()) {
      appendComment(out, depth, "(requires restart)");
    }
    Object value = key.defaultValue();
    if (value instanceof List<?> list) {
      indent(out, depth).append(key.name()).append(':');
      if (list.isEmpty()) {
        out.append(" []").append(System.lineSeparator());
        return;
      }
      out.append(System.lineSeparator());
      for (Object element : list) {
        indent(out, depth + 1).append("- ").append(scalar(element)).append(System.lineSeparator());
      }
      return;
    }
    indent(out, depth)
        .append(key.name())
        .append(": ")
        .append(scalar(value))
        .append(System.lineSeparator());
  }

  /**
   * Renders one scalar the way its codec reads it back: enums by their lower-cased constant name
   * (decoding is case-insensitive), strings quoted so nothing is mistaken for a number or boolean.
   */
  private static String scalar(Object value) {
    if (value instanceof Enum<?> constant) {
      return constant.name().toLowerCase(Locale.ROOT);
    }
    if (value instanceof Boolean || value instanceof Number) {
      return String.valueOf(value);
    }
    return '"' + String.valueOf(value).replace("\\", "\\\\").replace("\"", "\\\"") + '"';
  }

  private static void appendComment(StringBuilder out, int depth, String line) {
    indent(out, depth).append('#');
    if (!line.isEmpty()) {
      out.append(' ').append(line);
    }
    out.append(System.lineSeparator());
  }

  private static StringBuilder indent(StringBuilder out, int depth) {
    return out.append(" ".repeat(depth * INDENT));
  }

  /** One node of the path tree: either an intermediate section or (when {@code key}) a leaf. */
  private static final class Node {

    private final String path;
    private final Map<String, Node> children = new LinkedHashMap<>();
    private ConfigKey<?> key;

    private Node(String path) {
      this.path = path;
    }

    private void insert(ConfigKey<?> registered, String[] segments, int index) {
      String segment = segments[index];
      String childPath = path == null ? segment : path + '.' + segment;
      Node child = children.computeIfAbsent(segment, ignored -> new Node(childPath));
      if (index == segments.length - 1) {
        child.key = registered;
        return;
      }
      child.insert(registered, segments, index + 1);
    }
  }
}
