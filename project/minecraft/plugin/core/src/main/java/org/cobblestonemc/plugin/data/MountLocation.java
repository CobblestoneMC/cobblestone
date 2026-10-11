/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.data;

import java.util.UUID;

/**
 * The mount a player last rode, and where it was last seen, kept so they can navigate back to it
 * ({@code /navigate mount}).
 *
 * <p>Exactly one record exists per player: each dismount overwrites the previous one. The position
 * is where the mount was when the player got off, or when its chunk was last unloaded, whichever
 * came later; while the mount is loaded the plugin layer follows the live entity instead.
 *
 * @param player the rider
 * @param mount the mount's entity id
 * @param world the namespaced world key (e.g. {@code minecraft:overworld})
 * @param x the block x-coordinate
 * @param y the block y-coordinate
 * @param z the block z-coordinate
 */
public record MountLocation(UUID player, UUID mount, String world, int x, int y, int z) {}
