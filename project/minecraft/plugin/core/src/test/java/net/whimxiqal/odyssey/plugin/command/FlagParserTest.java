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
    Parsed parsed = parsed("waypoint", "home");
    assertEquals(List.of("waypoint", "home"), parsed.destination());
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
}
