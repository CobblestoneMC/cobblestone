/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.minecraft.api;

/**
 * The concrete instruction payload for Minecraft steps — the {@code I} generic.
 *
 * <p>Most steps carry a {@code null} instruction; parameterless actions (place a boat, mount a
 * horse, open a door) are conveyed by {@link MinecraftStepType} alone. This sealed set exists for
 * the payload-bearing case, chiefly {@link CommandInstruction}. A navigator switches over it
 * exhaustively to decide how to prompt the player.
 */
public sealed interface MinecraftInstruction
    permits MinecraftInstruction.None, MinecraftInstruction.CommandInstruction {

  record None() implements MinecraftInstruction {}

  /**
   * Instructs the player to run a command (e.g. {@code /home}) to traverse a transition.
   *
   * @param command the command to run, including the leading slash
   */
  record CommandInstruction(String command) implements MinecraftInstruction {}
}
