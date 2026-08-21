/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.plugin.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepType;
import net.whimxiqal.odyssey.plugin.command.FlagParser.Error;
import net.whimxiqal.odyssey.plugin.command.FlagParser.Invalid;
import net.whimxiqal.odyssey.plugin.command.FlagParser.Parsed;
import net.whimxiqal.odyssey.plugin.command.FlagParser.Result;
import net.whimxiqal.odyssey.plugin.command.NavigationFlags.Liveness;
import org.junit.jupiter.api.Test;

/** Flag splitting, defaults, mode aliases, and structured errors for {@link FlagParser}. */
class FlagParserTest {

  private static Parsed parsed(String... tokens) {
    Result result = FlagParser.parse(List.of(tokens));
    return assertInstanceOf(Parsed.class, result);
  }

  @Test
  void positionalArgumentsAndDefaults() {
    Parsed parsed = parsed("location", "home");
    assertEquals(List.of("location", "home"), parsed.destination());
    assertEquals(FlagParser.DEFAULT_NAVIGATOR, parsed.flags().navigator());
    assertEquals(Liveness.DEFAULT, parsed.flags().liveness());
    assertTrue(parsed.flags().excludedModes().isEmpty());
  }

  @Test
  void liveAndNavigatorFlags() {
    Parsed parsed = parsed("home", "-live", "-navigator", "compass");
    assertEquals(List.of("home"), parsed.destination());
    assertEquals(Liveness.LIVE, parsed.flags().liveness());
    assertEquals("compass", parsed.flags().navigator());
    assertEquals(Liveness.NO_LIVE, parsed("home", "-no-live").flags().liveness());
  }

  @Test
  void modeExclusionsFromAliasAndExplicitFlag() {
    Parsed parsed = parsed("home", "-no-fly", "-no-mode", "boat");
    assertEquals(
        Set.of(MinecraftStepType.FLY, MinecraftStepType.BOAT), parsed.flags().excludedModes());
    // "door" maps to OPEN_DOOR.
    assertEquals(
        Set.of(MinecraftStepType.OPEN_DOOR), parsed("home", "-no-door").flags().excludedModes());
  }

  @Test
  void worldAndDimensionExclusions() {
    Parsed parsed = parsed("home", "-no-world", "world_nether", "-no-dimension", "the_end");
    assertEquals(Set.of("world_nether"), parsed.flags().excludedWorlds());
    assertEquals(Set.of("the_end"), parsed.flags().excludedDimensions());
  }

  @Test
  void unknownFlagIsReported() {
    Result result = FlagParser.parse(List.of("home", "-turbo"));
    Invalid invalid = assertInstanceOf(Invalid.class, result);
    assertEquals(Error.UNKNOWN_FLAG, invalid.error());
    assertEquals("-turbo", invalid.token());
  }

  @Test
  void missingValueIsReported() {
    Invalid invalid =
        assertInstanceOf(Invalid.class, FlagParser.parse(List.of("home", "-navigator")));
    assertEquals(Error.MISSING_VALUE, invalid.error());
    assertEquals("-navigator", invalid.token());
  }

  @Test
  void unknownModeIsReported() {
    Invalid invalid =
        assertInstanceOf(Invalid.class, FlagParser.parse(List.of("home", "-no-mode", "teleport")));
    assertEquals(Error.UNKNOWN_MODE, invalid.error());
    assertEquals("teleport", invalid.token());
  }

  @Test
  void trailingSpaceBecomesAnEmptyTokenForCompletion() {
    // Tab-completion hinges on this: "mco " must leave an empty last token, or the completer would
    // re-offer "mco" instead of moving on to what lives under it.
    assertEquals(List.of(""), FlagParser.tokenizeKeepingTrailing(""));
    assertEquals(List.of("mco"), FlagParser.tokenizeKeepingTrailing("mco"));
    assertEquals(List.of("mco", ""), FlagParser.tokenizeKeepingTrailing("mco "));
    assertEquals(List.of("mco", "wa"), FlagParser.tokenizeKeepingTrailing("mco wa"));
    assertEquals(List.of("mco", "warp", ""), FlagParser.tokenizeKeepingTrailing("mco warp "));
  }

  @Test
  void destinationTokensDropFlagsAndTheirValues() {
    assertEquals(
        List.of("mco", ""),
        FlagParser.destinationTokens(FlagParser.tokenizeKeepingTrailing("-live mco ")));
    assertEquals(
        List.of("mco", ""),
        FlagParser.destinationTokens(FlagParser.tokenizeKeepingTrailing("-navigator trail mco ")));
    assertEquals(
        List.of("mco", "wa"),
        FlagParser.destinationTokens(FlagParser.tokenizeKeepingTrailing("-no-mode mine mco wa")));
    // A flag being typed is not a destination token, but the empty token after it still is.
    assertEquals(List.of("mco"), FlagParser.destinationTokens(List.of("mco", "-liv")));
    // Case does not matter for flags.
    assertEquals(
        List.of("mco"), FlagParser.destinationTokens(List.of("-NAVIGATOR", "trail", "mco")));
  }

  @Test
  void destinationTokensMatchWhatParsingWouldHaveKept() {
    List<String> tokens = List.of("-live", "mco", "-no-mode", "mine", "warp", "spawn");
    Parsed parsed = parsed(tokens.toArray(new String[0]));
    assertEquals(parsed.destination(), FlagParser.destinationTokens(tokens));
  }
}
