/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.minecraft;

import net.whimxiqal.odyssey.Cell;
import net.whimxiqal.odyssey.FutureOr;

/**
 * Decides whether the mining mode may break a given block — the injection point for integrations that
 * forbid breaking certain blocks: region-protection plugins (a Towny town bars non-residents) or
 * server rules (a griefing-sensitive server bars breaking man-made block types).
 *
 * <p>The verdict is a {@link FutureOr} so a check may resolve asynchronously (e.g. a protection
 * plugin's permission lookup against its database); an immediate answer never parks the search.
 * Distinct from {@link MinecraftAgent#canBreak} (the coarse per-agent gate); both must allow.
 *
 * @param <A> the agent type
 */
public interface BreakChecker<A extends MinecraftAgent> {

  /**
   * Returns whether the agent may break the given block.
   *
   * @param agent the navigating agent
   * @param cell the block's cell
   * @param world the world the block is in
   * @param block the block (its type, hardness, etc.)
   * @return {@code true} if breaking is permitted
   */
  FutureOr<Boolean> breakable(A agent, Cell cell, MinecraftWorld world, MinecraftBlock block);

  /**
   * A checker that permits breaking everything (the default when no integration constrains mining).
   *
   * @param <A> the agent type
   * @return an allow-all checker
   */
  static <A extends MinecraftAgent> BreakChecker<A> allowAll() {
    return (agent, cell, world, block) -> FutureOr.of(true);
  }
}
