/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.plugin.api;

import net.whimxiqal.odyssey.api.Path;
import net.whimxiqal.odyssey.api.Step;
import net.whimxiqal.odyssey.minecraft.api.MinecraftInstruction;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepType;

/**
 * A convenience name for the path a {@link Navigator} follows: a {@link Path} of Minecraft
 * {@link Step}s located by the platform's native location type {@code L} (e.g.
 * {@code org.bukkit.Location}) — the same located-step shape a platform-API search returns.
 *
 * <p>The Trip layer (Phase 6c) adapts a core search result into a {@code MinecraftPath<L>} for the
 * navigator, so navigators render entirely in native terms.
 *
 * @param <L> the native location type
 */
public interface MinecraftPath<L>
    extends Path<Step<L, MinecraftStepType, MinecraftInstruction>> {
}
