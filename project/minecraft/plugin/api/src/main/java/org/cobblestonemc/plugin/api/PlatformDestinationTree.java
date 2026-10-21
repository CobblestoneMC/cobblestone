/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.api;

/**
 * One level of an integration's destination tree, in the platform's own world and vector types —
 * what a platform's {@code DestinationService} hands Cobblestone, and what its {@code
 * DestinationTree} builder builds.
 *
 * @param <W> the platform world type
 * @param <V> the platform vector type
 */
public interface PlatformDestinationTree<W, V>
    extends DestinationNode<MinecraftDestination<W, V>> {}
