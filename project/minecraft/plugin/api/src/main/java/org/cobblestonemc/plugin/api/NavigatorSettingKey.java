/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.api;

/**
 * A typed key for a navigator setting (e.g. the trail's particle types). Keys carry the value's
 * type so {@link NavigatorSettings#get} returns it without an unchecked cast — a navigator defines
 * the keys it understands, and callers set them through the navigator's settings builder.
 *
 * @param name a unique, human-readable key name (used only for identity/toString)
 * @param <T> the value type
 */
public record NavigatorSettingKey<T>(String name) {}
