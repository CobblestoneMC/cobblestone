/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.plugin.data;

/**
 * A learned end-return portal: the exit portal in the End, which teleports each player to their own
 * respawn point (bed/anchor, else the world spawn) rather than a fixed location. Because the
 * destination is per-player, only the portal's {@link PortalRegion region} is cached here; the
 * arrival is resolved at search time from the routed player's respawn location.
 *
 * @param region the portal's region, in the End
 * @param cost the traversal cost in seconds
 */
public record EndReturnPortal(PortalRegion region, double cost) {}
