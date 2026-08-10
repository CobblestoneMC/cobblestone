/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.minecraft.modes;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import net.whimxiqal.odyssey.Cell;
import net.whimxiqal.odyssey.FutureOr;
import net.whimxiqal.odyssey.Movement;
import net.whimxiqal.odyssey.api.TraversalState;
import net.whimxiqal.odyssey.minecraft.MinecraftAgent;
import net.whimxiqal.odyssey.minecraft.api.MinecraftInstruction;
import net.whimxiqal.odyssey.minecraft.MinecraftMode;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepPayload;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepType;
import net.whimxiqal.odyssey.minecraft.MinecraftWorld;

/**
 * Base class for the Minecraft modes. It handles the common plumbing — gating by state, fetching the
 * neighborhood of blocks a step needs, and mapping the result — so subclasses only declare which
 * cells they inspect and how they turn those blocks into movements.
 *
 * @param <A> the agent type (modes consume the agent, so they are generic over it)
 */
abstract class AbstractMinecraftMode<A extends MinecraftAgent> implements MinecraftMode<A> {

  @Override
  public final FutureOr<Collection<Movement<MinecraftStepPayload>>> step(
      A agent, Cell from, MinecraftWorld world, TraversalState state) {
    if (!applies(agent, state)) {
      return FutureOr.of(List.of());
    }
    return BlockLookup.fetch(world, requiredCells(from))
        .flatMap(view -> movements(agent, from, world, state, view));
  }

  /**
   * Whether this mode can produce any movement in the given state (cheap gate to avoid a block
   * fetch). Defaults to always.
   */
  protected boolean applies(A agent, TraversalState state) {
    return true;
  }

  /** The cells this mode needs to inspect around {@code from}. */
  protected abstract Set<Cell> requiredCells(Cell from);

  /**
   * Async seam over the fetched blocks. The default wraps the synchronous {@link #computeMovements};
   * modes that must consult an asynchronous check (mining's breakability lookups) override this and
   * return a possibly-pending {@link FutureOr}.
   */
  protected FutureOr<Collection<Movement<MinecraftStepPayload>>> movements(
      A agent, Cell from, MinecraftWorld world, TraversalState state, BlockView view) {
    return FutureOr.of(computeMovements(agent, from, state, view));
  }

  /**
   * Turns the fetched blocks into the movements this mode offers from {@code from}. Synchronous
   * modes override this; asynchronous ones override {@link #movements} instead and leave this at its
   * (empty) default.
   */
  protected Collection<Movement<MinecraftStepPayload>> computeMovements(
      A agent, Cell from, TraversalState state, BlockView view) {
    return List.of();
  }

  /** Builds a movement with no instruction. Time equals cost until danger weighting diverges them. */
  protected static Movement<MinecraftStepPayload> move(
      Cell cell, double cost, MinecraftStepType type, TraversalState state) {
    return new Movement<>(cell, cost, cost, new MinecraftStepPayload(type, new MinecraftInstruction.None()), state);
  }

  /** Builds a movement carrying an instruction. Time equals cost until danger weighting diverges them. */
  protected static Movement<MinecraftStepPayload> move(
      Cell cell, double cost, MinecraftStepType type, TraversalState state, MinecraftInstruction instruction) {
    return new Movement<>(cell, cost, cost, new MinecraftStepPayload(type, instruction), state);
  }
}
