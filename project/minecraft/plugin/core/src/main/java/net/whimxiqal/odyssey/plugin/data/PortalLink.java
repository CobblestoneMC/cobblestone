/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.plugin.data;

/**
 * One cell of a nether portal's destination partition: entering the source portal within {@code
 * subRegion} teleports to the {@code dest} portal. A single source portal owns one link per
 * distinct destination — because which portal you arrive at depends on which block you enter
 * (Minecraft scales your position by the coordinate ratio and links to the nearest portal), so the
 * source portal's blocks partition into contiguous sub-regions, one per destination. The navigable
 * arrival is the destination portal's centre at ground level (derived, not stored).
 *
 * @param source the whole source portal
 * @param subRegion the entry cells (a sub-box of {@code source}) that lead to {@code dest}
 * @param dest the destination portal
 * @param cost the traversal cost in seconds
 */
public record PortalLink(
    PortalRegion source, PortalRegion subRegion, PortalRegion dest, double cost) {}
