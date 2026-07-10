/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.minecraft.api;

import net.whimxiqal.odyssey.api.Agent;
import net.whimxiqal.odyssey.api.Cell;

/**
 * A Minecraft {@link Agent}: anything that can be navigated (usually an {@link OdysseyPlayer}).
 *
 * <p>Capability gating that decides <i>which</i> modes an agent has (e.g. flying) happens when the
 * mode list is assembled, not here. The methods on this interface are ones a mode may consult
 * <i>during</i> a step.
 */
public interface MinecraftAgent extends Agent {

  /**
   * Returns whether the agent is permitted to break the block at {@code cell} (region protection,
   * claims, etc.). The v1 default from platform wrappers is {@code true}; region-plugin integrations
   * refine it later.
   *
   * @param cell the cell to break
   * @return {@code true} if breaking is allowed
   */
  boolean canBreak(Cell cell);
}
