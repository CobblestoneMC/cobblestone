/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.copier;

import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * The trailing flags of {@code /copier copy} and {@code /copier estimate}.
 *
 * <p>Parsed from a greedy string rather than modelled as Brigadier arguments: the flags are
 * mutually constraining ({@code --surface}, {@code --y} and {@code --full} pick one of three), and
 * expressing that as a literal tree would produce a combinatorial thicket of branches for a
 * developer tool that is typed by two people.
 *
 * @param captureName the capture directory name
 * @param worldKey the world to capture, or {@code null} for the sender's own
 * @param blockCoordinates whether the rectangle is in block rather than chunk coordinates
 * @param vertical how much of each column to capture
 * @param overwrite whether an existing capture may be written into
 * @param force whether the chunk-count guard is waived
 * @param generate whether chunks that do not exist yet may be generated
 */
record CopierOptions(
    String captureName,
    @Nullable String worldKey,
    boolean blockCoordinates,
    VerticalMode vertical,
    boolean overwrite,
    boolean force,
    boolean generate) {

  /** Thrown when the flags cannot be understood; the message is shown to the sender verbatim. */
  static final class ParseException extends Exception {

    private static final long serialVersionUID = 1L;

    ParseException(String message) {
      super(message);
    }
  }

  /**
   * Parses the trailing flags.
   *
   * @param raw the flag text, possibly empty
   * @param defaultName the capture name to use when {@code --name} is absent
   * @return the options
   * @throws ParseException if a flag is unknown, incomplete, or contradicts another
   */
  static CopierOptions parse(String raw, String defaultName) throws ParseException {
    List<String> tokens = new ArrayList<>();
    for (String token : raw.trim().split("\\s+")) {
      if (!token.isEmpty()) {
        tokens.add(token);
      }
    }

    String name = defaultName;
    String world = null;
    boolean blocks = false;
    boolean overwrite = false;
    boolean force = false;
    boolean generate = false;
    VerticalMode vertical = null;

    for (int i = 0; i < tokens.size(); i++) {
      String token = tokens.get(i);
      switch (token) {
        case "--name" -> name = require(tokens, ++i, "--name needs a capture name");
        case "--world" -> world = require(tokens, ++i, "--world needs a world key");
        case "--blocks" -> blocks = true;
        case "--overwrite" -> overwrite = true;
        case "--force" -> force = true;
        case "--generate" -> generate = true;
        case "--full" -> vertical = requireUnsetVertical(vertical, new VerticalMode.Full());
        case "--surface" -> {
          int below = requireInt(tokens, ++i, "--surface needs two numbers: <below> <above>");
          int above = requireInt(tokens, ++i, "--surface needs two numbers: <below> <above>");
          if (below < 0 || above < 0) {
            throw new ParseException("--surface distances cannot be negative");
          }
          vertical = requireUnsetVertical(vertical, new VerticalMode.Surface(below, above));
        }
        case "--y" -> {
          int minY = requireInt(tokens, ++i, "--y needs two numbers: <minY> <maxY>");
          int maxY = requireInt(tokens, ++i, "--y needs two numbers: <minY> <maxY>");
          if (minY > maxY) {
            throw new ParseException("--y wants the lower bound first: " + minY + " > " + maxY);
          }
          vertical = requireUnsetVertical(vertical, new VerticalMode.Range(minY, maxY));
        }
        default -> throw new ParseException("unknown option '" + token + "'");
      }
    }
    return new CopierOptions(
        name,
        world,
        blocks,
        vertical == null ? VerticalMode.defaultMode() : vertical,
        overwrite,
        force,
        generate);
  }

  private static VerticalMode requireUnsetVertical(
      @Nullable VerticalMode current, VerticalMode next) throws ParseException {
    if (current != null) {
      throw new ParseException(
          "pick one of --surface, --y or --full; you gave both "
              + current.toCommandArguments()
              + " and "
              + next.toCommandArguments());
    }
    return next;
  }

  private static String require(List<String> tokens, int index, String message)
      throws ParseException {
    if (index >= tokens.size()) {
      throw new ParseException(message);
    }
    return tokens.get(index);
  }

  private static int requireInt(List<String> tokens, int index, String message)
      throws ParseException {
    String token = require(tokens, index, message);
    try {
      return Integer.parseInt(token);
    } catch (NumberFormatException e) {
      throw new ParseException(message + " (got '" + token + "')");
    }
  }
}
