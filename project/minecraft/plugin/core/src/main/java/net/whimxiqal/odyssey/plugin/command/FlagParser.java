/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.plugin.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepType;

/**
 * Splits the raw tokens of a {@code /navigate} invocation into the positional destination arguments
 * and the {@link NavigationFlags}. Any token starting with {@code -} is a flag; everything else is
 * a destination token (fed to the {@code DestinationResolver}).
 *
 * <p>Recognized flags: {@code -navigator <id>}, {@code -no-world <world>}, {@code -no-dimension
 * <dim>}, {@code -no-mode <mode>}, {@code -live}, plus per-mode aliases {@code -no-<mode>} (e.g.
 * {@code -no-fly} ⇒ {@code -no-mode fly}). Parsing is platform-neutral and returns structured
 * errors so the command layer can localize them.
 */
public final class FlagParser {

  /** The default display strategy when {@code -navigator} is not given. */
  public static final String DEFAULT_NAVIGATOR = "trail";

  /** Mode words accepted by {@code -no-mode}/{@code -no-<mode>}, mapped to their step type. */
  private static final Map<String, MinecraftStepType> MODE_WORDS = new LinkedHashMap<>();

  /** Flags that consume the token after them, which is therefore not a destination token. */
  private static final Set<String> VALUE_FLAGS =
      Set.of("-navigator", "-no-world", "-no-dimension", "-no-mode");

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

  private FlagParser() {}

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
  public sealed interface Result {}

  /**
   * A successful parse.
   *
   * @param destination the positional destination tokens, in order
   * @param flags the parsed flags
   */
  public record Parsed(List<String> destination, NavigationFlags flags) implements Result {}

  /**
   * A failed parse.
   *
   * @param error what went wrong
   * @param token the offending token (the flag, or the bad value)
   */
  public record Invalid(Error error, String token) implements Result {}

  /** The set of accepted mode words (for tab-completion of {@code -no-mode}). */
  public static Set<String> modeWords() {
    return Set.copyOf(MODE_WORDS.keySet());
  }

  /** The set of flags that take a following value (for tab-completion). */
  public static Set<String> valueFlags() {
    return VALUE_FLAGS;
  }

  /**
   * Splits raw command input into tokens, <b>keeping</b> the empty token that a trailing space
   * implies. Tab-completion needs that token: it is the (still empty) word being typed, and without
   * it the completer would re-offer the word before it instead of moving on.
   *
   * @param raw the raw argument string, exactly as typed
   * @return the tokens, never empty
   */
  public static List<String> tokenizeKeepingTrailing(String raw) {
    if (raw.isEmpty()) {
      return List.of("");
    }
    // -1 keeps the trailing empty string; a leading one appears only if the input starts blank.
    List<String> tokens = new ArrayList<>(Arrays.asList(raw.split("\\s+", -1)));
    if (tokens.isEmpty()) {
      tokens.add("");
    }
    return tokens;
  }

  /**
   * Keeps only the positional destination tokens: flags and the values they consume are dropped, so
   * {@code -live mco warp} and {@code mco warp} address the same place.
   *
   * @param tokens the raw tokens
   * @return the destination tokens, in order
   */
  public static List<String> destinationTokens(List<String> tokens) {
    List<String> out = new ArrayList<>();
    boolean skipValue = false;
    for (String token : tokens) {
      if (skipValue) {
        skipValue = false;
        continue;
      }
      if (VALUE_FLAGS.contains(token.toLowerCase(Locale.ROOT))) {
        skipValue = true;
      } else if (!token.startsWith("-")) {
        out.add(token);
      }
    }
    return out;
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

    return new Parsed(
        List.copyOf(destination),
        new NavigationFlags(
            excludedModes, excludedWorlds, excludedDimensions, navigator, liveness));
  }
}
