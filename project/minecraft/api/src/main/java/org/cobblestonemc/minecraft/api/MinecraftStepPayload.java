/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.api;

/**
 * The payload attached to every Minecraft step: what kind of step it is and, for the few
 * instruction-bearing kinds, the concrete instruction.
 *
 * @param stepType the step type
 * @param instruction the instruction payload (never {@code null}; use {@link
 *     MinecraftInstruction.None} for the common parameterless case)
 */
public record MinecraftStepPayload(MinecraftStepType stepType, MinecraftInstruction instruction) {

  /**
   * A payload for a step type that carries no instruction (walk, jump, portal, mount a horse, …).
   *
   * @param stepType the step type
   * @return the payload
   */
  public static MinecraftStepPayload of(MinecraftStepType stepType) {
    return new MinecraftStepPayload(stepType, MinecraftInstruction.None.INSTANCE);
  }

  /**
   * A {@link MinecraftStepType#TELEPORT} payload — arrive by walking into a portal/pad.
   *
   * @return the payload
   */
  public static MinecraftStepPayload portal() {
    return of(MinecraftStepType.TELEPORT);
  }

  /**
   * A {@link MinecraftStepType#TELEPORT} payload instructing the player to run the given command.
   *
   * @param command the command to run, including the leading slash (e.g. {@code /warp home})
   * @return the payload
   */
  public static MinecraftStepPayload command(String command) {
    return new MinecraftStepPayload(
        MinecraftStepType.TELEPORT, new MinecraftInstruction.CommandInstruction(command));
  }
}
