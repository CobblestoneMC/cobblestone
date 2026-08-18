/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.sponge12;

import net.whimxiqal.odyssey.minecraft.MinecraftWorld;
import org.spongepowered.api.world.server.ServerWorld;

/**
 * Wraps a Sponge {@link ServerWorld} into Odyssey's {@link MinecraftWorld} (cached, one per key).
 */
public interface WorldWrapper {

  MinecraftWorld wrap(ServerWorld world);
}
