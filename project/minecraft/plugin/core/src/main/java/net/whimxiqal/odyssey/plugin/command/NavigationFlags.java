/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.plugin.command;

import java.util.Set;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepType;

/**
 * The resolved options a {@code /navigate} invocation carries, produced by {@link FlagParser}. The
 * {@link #excludedModes()} set feeds straight into {@code MinecraftModes.forPlayer(player, excluded)};
 * the world/dimension exclusions and navigator/live choices are honored by the command layer.
 *
 * @param excludedModes step types to leave out of the search (from {@code -no-mode}/{@code -no-fly}…)
 * @param excludedWorlds world keys to exclude from routing (from {@code -no-world})
 * @param excludedDimensions dimension names to exclude from routing (from {@code -no-dimension})
 * @param navigator the display strategy id (default {@link FlagParser#DEFAULT_NAVIGATOR})
 * @param live whether the resulting trip should auto-recalculate
 */
public record NavigationFlags(
    Set<MinecraftStepType> excludedModes,
    Set<String> excludedWorlds,
    Set<String> excludedDimensions,
    String navigator,
    boolean live) {

  /** Canonical constructor; defensively copies the exclusion sets. */
  public NavigationFlags {
    excludedModes = Set.copyOf(excludedModes);
    excludedWorlds = Set.copyOf(excludedWorlds);
    excludedDimensions = Set.copyOf(excludedDimensions);
  }
}
