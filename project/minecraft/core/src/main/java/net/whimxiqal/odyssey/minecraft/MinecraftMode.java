/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.minecraft;

import net.whimxiqal.odyssey.Mode;
import net.whimxiqal.odyssey.minecraft.api.MinecraftInstruction;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepPayload;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepType;

/**
 * A {@link Mode} bound to the Minecraft generics: {@link MinecraftStepType}, {@link
 * MinecraftInstruction}, and {@link MinecraftWorld}.
 *
 * @param <A> the agent type
 */
public interface MinecraftMode<A extends MinecraftAgent>
    extends Mode<A, MinecraftStepPayload, MinecraftWorld> {}
