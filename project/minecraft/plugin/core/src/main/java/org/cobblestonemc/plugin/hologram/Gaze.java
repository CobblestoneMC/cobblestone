/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.hologram;

import org.cobblestonemc.plugin.navigator.Vec3;

/**
 * Where a player is looking from, and where to.
 *
 * @param eye the position of the player's eyes
 * @param look the unit direction they are looking in
 */
public record Gaze(Vec3 eye, Vec3 look) {}
