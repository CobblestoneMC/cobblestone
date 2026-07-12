/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper;

import java.util.List;
import net.whimxiqal.odyssey.api.Path;
import net.whimxiqal.odyssey.api.Step;
import net.whimxiqal.odyssey.minecraft.api.MinecraftInstruction;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepType;
import org.bukkit.Location;

/**
 * The native-located {@link Path} handed to Paper developers: each {@link Step} carries a Bukkit
 * {@link Location}. Built by mapping a solved core path's positions through {@link PaperConversions}.
 *
 * @param steps the ordered steps, origin first
 * @param cost the total cost in seconds
 */
record PaperPath(List<Step<Location, MinecraftStepType, MinecraftInstruction>> steps, double cost)
    implements Path<Step<Location, MinecraftStepType, MinecraftInstruction>> {

  PaperPath {
    steps = List.copyOf(steps);
  }
}
