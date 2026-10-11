/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.data;

import java.util.UUID;

/**
 * A mount a player has ridden, and where it was last seen, kept so they can navigate back to it
 * ({@code /navigate mount}).
 *
 * <p>Each player has one record per mount, refreshed every time they get off it; the newest is the
 * one {@code mount} leads to. The position is where the mount was when the player got off, or when
 * its chunk was last unloaded, whichever came later; while the mount is loaded the plugin layer
 * follows the live entity instead.
 *
 * @param player the rider
 * @param mount the mount's entity id
 * @param world the namespaced world key (e.g. {@code minecraft:overworld})
 * @param x the block x-coordinate
 * @param y the block y-coordinate
 * @param z the block z-coordinate
 * @param riddenAt when the player last got off it, in epoch milliseconds
 */
public record MountLocation(
    UUID player, UUID mount, String world, int x, int y, int z, long riddenAt) {}
