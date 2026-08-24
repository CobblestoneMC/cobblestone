/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.data;

import java.util.UUID;

/**
 * Where a player most recently died, kept so they can navigate back to it ({@code /navigate
 * death}).
 *
 * <p>Exactly one record exists per player: each death overwrites the previous one. Like {@link
 * Location} it stores only a world key and integer block coordinates, so the record survives
 * restarts and is platform-neutral; the plugin layer re-hydrates it into a live {@code
 * MinecraftDestination} when a search is requested.
 *
 * @param player the player who died
 * @param world the namespaced world key (e.g. {@code minecraft:overworld})
 * @param x the block x-coordinate
 * @param y the block y-coordinate
 * @param z the block z-coordinate
 */
public record DeathLocation(UUID player, String world, int x, int y, int z) {}
