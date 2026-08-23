/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper;

import org.bukkit.World;
import org.cobblestonemc.minecraft.MinecraftWorld;

public interface WorldWrapper {

  MinecraftWorld wrap(World world);
}
