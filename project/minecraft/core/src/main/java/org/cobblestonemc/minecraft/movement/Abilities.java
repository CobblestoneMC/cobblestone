/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.movement;

/**
 * What one agent may do on a search, settled once when its {@link MinecraftMovementBehavior} is
 * built — from the agent's capabilities and the step types the search excludes.
 *
 * @param walk walk, jump and step down a block
 * @param doors step through a doorway at all (an open door needs nothing more)
 * @param openDoors work a shut door
 * @param mine tunnel through breakable blocks
 * @param fall drop two or more blocks
 * @param swim swim through water
 * @param climb climb ladders, vines and scaffolding
 * @param horse ride a horse once mounted
 * @param flightHeight the body height a flier needs (2 flying, 1 gliding), or 0 if it cannot fly
 * @param boat board and travel by boat
 * @param enderPearls how many ender pearls may be thrown
 */
record Abilities(
    boolean walk,
    boolean doors,
    boolean openDoors,
    boolean mine,
    boolean fall,
    boolean swim,
    boolean climb,
    boolean horse,
    int flightHeight,
    boolean boat,
    int enderPearls) {}
