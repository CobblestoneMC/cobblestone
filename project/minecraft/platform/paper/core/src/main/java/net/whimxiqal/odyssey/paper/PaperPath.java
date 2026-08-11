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
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepPayload;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepType;
import org.bukkit.Location;

/**
 * The native-located {@link Path} handed to Paper developers: each {@link Step} carries a Bukkit
 * {@link Location}. Built by mapping a solved core path's positions through {@link PaperConversions}.
 *
 * <p>{@link Path#cost()}/{@link Path#duration()} are derived from the steps by the interface
 * defaults, so this record stores only the step list.
 *
 * @param steps the ordered steps, origin first
 */
record PaperPath(Location origin, List<Step<Location, MinecraftStepPayload>> steps)
    implements Path<Location, MinecraftStepPayload> {

  PaperPath {
    steps = List.copyOf(steps);
  }

}
