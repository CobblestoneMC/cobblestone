/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.movement;

/**
 * What a player may do on a search, settled when its {@link MinecraftMovementBehavior} is built.
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
 * @param boardBoat place a boat from the inventory and board it
 * @param paddle travel by boat once in one
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
    boolean boardBoat,
    boolean paddle,
    int enderPearls) {}
