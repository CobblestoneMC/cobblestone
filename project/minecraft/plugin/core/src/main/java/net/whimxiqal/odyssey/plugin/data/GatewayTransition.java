/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.plugin.data;

/**
 * A learned end-gateway link: entering the gateway block teleports to an exit point. Gateways carry
 * a readable exit, but reading it at search-build time would mean fetching every gateway block
 * entity, so Odyssey caches the exit here (learned from a player's teleport) and updates it if a
 * later teleport lands somewhere different. Keyed by the gateway block ({@code world, x, y, z}).
 *
 * @param world the gateway's world key
 * @param x the gateway block x
 * @param y the gateway block y
 * @param z the gateway block z
 * @param toWorld the exit world key
 * @param toX the exit x
 * @param toY the exit y
 * @param toZ the exit z
 * @param cost the traversal cost in seconds
 */
public record GatewayTransition(
    String world, int x, int y, int z, String toWorld, int toX, int toY, int toZ, double cost) {}
