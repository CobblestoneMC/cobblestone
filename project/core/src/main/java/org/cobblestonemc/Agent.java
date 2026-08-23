/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc;

/**
 * Marker for the entity that is navigating.
 *
 * <p>Intentionally empty at the core level: it exists so {@link Mode}s can be typed against a
 * concrete agent downstream (via the {@code A} generic) without casting. Capability accessors (e.g.
 * {@code canFly()}) are added by subtypes such as {@code MinecraftAgent} and {@code
 * CobblestonePlayer}.
 */
public interface Agent {}
