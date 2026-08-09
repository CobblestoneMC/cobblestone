/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.plugin.command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepType;

/**
 * Splits the raw tokens of a {@code /navigate} invocation into the positional destination arguments
 * and the {@link NavigationFlags}. Any token starting with {@code -} is a flag; everything else is a
 * destination token (fed to the {@code DestinationResolver}).
 *
 * <p>Recognized flags: {@code -navigator <id>}, {@code -no-world <world>}, {@code -no-dimension
 * <dim>}, {@code -no-mode <mode>}, {@code -live}, plus per-mode aliases {@code -no-<mode>} (e.g.
 * {@code -no-fly} ⇒ {@code -no-mode fly}). Parsing is platform-neutral and returns structured errors
 * so the command layer can localize them.
 */
public final class FlagParser {

  /** The default display strategy when {@code -navigator} is not given. */
  public static final String DEFAULT_NAVIGATOR = "trail";

  /** Mode words accepted by {@code -no-mode}/{@code -no-<mode>}, mapped to their step type. */
  private static final Map<String, MinecraftStepType> MODE_WORDS = new LinkedHashMap<>();

  static {
    MODE_WORDS.put("walk", MinecraftStepType.WALK);
    MODE_WORDS.put("swim", MinecraftStepType.SWIM);
    MODE_WORDS.put("fly", MinecraftStepType.FLY);
    MODE_WORDS.put("mine", MinecraftStepType.MINE);
    MODE_WORDS.put("fall", MinecraftStepType.FALL);
    MODE_WORDS.put("climb", MinecraftStepType.CLIMB);
    MODE_WORDS.put("boat", MinecraftStepType.BOAT);
    MODE_WORDS.put("horse", MinecraftStepType.HORSE);
    MODE_WORDS.put("door", MinecraftStepType.OPEN_DOOR);
  }

  private FlagParser() {
  }

  /** Why a parse failed. */
  public enum Error {
    /** A {@code -flag} was not recognized. */
    UNKNOWN_FLAG,
    /** A value-taking flag was the last token, with no value after it. */
    MISSING_VALUE,
    /** A {@code -no-mode} value was not a known mode word. */
    UNKNOWN_MODE
  }

  /** The result of a parse: {@link Parsed} on success, {@link Invalid} otherwise. */
  public sealed interface Result {
  }

  /**
   * A successful parse.
   *
   * @param destination the positional destination tokens, in order
   * @param flags the parsed flags
   */
  public record Parsed(List<String> destination, NavigationFlags flags) implements Result {
  }

  /**
   * A failed parse.
   *
   * @param error what went wrong
   * @param token the offending token (the flag, or the bad value)
   */
  public record Invalid(Error error, String token) implements Result {
  }

  /** The set of accepted mode words (for tab-completion of {@code -no-mode}). */
  public static Set<String> modeWords() {
    return Set.copyOf(MODE_WORDS.keySet());
  }

  /**
   * Parses the raw argument tokens.
   *
   * @param tokens the tokens after {@code /navigate}
   * @return the result
   */
  public static Result parse(List<String> tokens) {
    List<String> destination = new ArrayList<>();
    Set<MinecraftStepType> excludedModes = new LinkedHashSet<>();
    Set<String> excludedWorlds = new LinkedHashSet<>();
    Set<String> excludedDimensions = new LinkedHashSet<>();
    String navigator = DEFAULT_NAVIGATOR;
    NavigationFlags.Liveness liveness = NavigationFlags.Liveness.DEFAULT;

    int i = 0;
    while (i < tokens.size()) {
      String token = tokens.get(i);
      if (!token.startsWith("-")) {
        destination.add(token);
        i++;
        continue;
      }
      String flag = token.toLowerCase(Locale.ROOT);
      if (flag.equals("-live")) {
        liveness = NavigationFlags.Liveness.LIVE;
        i++;
      } else if (flag.equals("-no-live")) {
        liveness = NavigationFlags.Liveness.NO_LIVE;
        i++;
      } else if (flag.equals("-navigator")) {
        if (i + 1 >= tokens.size()) {
          return new Invalid(Error.MISSING_VALUE, token);
        }
        navigator = tokens.get(i + 1);
        i += 2;
      } else if (flag.equals("-no-world")) {
        if (i + 1 >= tokens.size()) {
          return new Invalid(Error.MISSING_VALUE, token);
        }
        excludedWorlds.add(tokens.get(i + 1));
        i += 2;
      } else if (flag.equals("-no-dimension")) {
        if (i + 1 >= tokens.size()) {
          return new Invalid(Error.MISSING_VALUE, token);
        }
        excludedDimensions.add(tokens.get(i + 1));
        i += 2;
      } else if (flag.equals("-no-mode")) {
        if (i + 1 >= tokens.size()) {
          return new Invalid(Error.MISSING_VALUE, token);
        }
        MinecraftStepType type = MODE_WORDS.get(tokens.get(i + 1).toLowerCase(Locale.ROOT));
        if (type == null) {
          return new Invalid(Error.UNKNOWN_MODE, tokens.get(i + 1));
        }
        excludedModes.add(type);
        i += 2;
      } else if (flag.startsWith("-no-") && MODE_WORDS.containsKey(flag.substring(4))) {
        excludedModes.add(MODE_WORDS.get(flag.substring(4)));
        i++;
      } else {
        return new Invalid(Error.UNKNOWN_FLAG, token);
      }
    }

    return new Parsed(List.copyOf(destination),
        new NavigationFlags(excludedModes, excludedWorlds, excludedDimensions, navigator, liveness));
  }
}
