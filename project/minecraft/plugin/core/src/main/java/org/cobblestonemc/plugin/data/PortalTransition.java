/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.data;

/**
 * A persisted, empirically-discovered one-way portal link: entering the source portal in one world
 * teleports the player to an arrival point (usually in another world). No platform API reveals
 * where a portal leads, so Cobblestone learns these by watching players teleport and records them
 * here; the reverse direction is only learned when someone travels back (nether linking is
 * asymmetric).
 *
 * <p>The source is stored as a block-coordinate bounding box (the portal frame) and is the row's
 * identity (from-world + minimum corner); the arrival is a single block coordinate. Re-walking a
 * portal whose destination changed updates the arrival for that source rather than adding a row.
 *
 * @param fromWorld the entry world's namespaced key
 * @param minX the entry box minimum x
 * @param minY the entry box minimum y
 * @param minZ the entry box minimum z
 * @param maxX the entry box maximum x
 * @param maxY the entry box maximum y
 * @param maxZ the entry box maximum z
 * @param toWorld the arrival world's namespaced key
 * @param toX the arrival x
 * @param toY the arrival y
 * @param toZ the arrival z
 * @param cost the traversal cost in seconds
 */
public record PortalTransition(
    String fromWorld,
    int minX,
    int minY,
    int minZ,
    int maxX,
    int maxY,
    int maxZ,
    String toWorld,
    int toX,
    int toY,
    int toZ,
    double cost) {}
