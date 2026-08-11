/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.minecraft;

import java.util.concurrent.CompletableFuture;
import net.whimxiqal.odyssey.Cell;

/**
 * Decides whether the mining mode may break a given block — the injection point for integrations that
 * forbid breaking certain blocks: region-protection plugins (a Towny town bars non-residents) or
 * server rules (a griefing-sensitive server bars breaking man-made block types).
 *
 * <p>The verdict is a {@link CompletableFuture} so a check may resolve asynchronously (e.g. a
 * protection plugin's permission lookup against its database). The mining mode does not wait on it: it
 * emits the mining edge optimistically and attaches the future to the {@link net.whimxiqal.odyssey.Movement},
 * so an already-completed future (a synchronous block-type rule) never parks the search. Distinct from
 * {@link MinecraftAgent#canBreak} (the coarse, synchronous per-agent gate); both must allow.
 *
 * <p>A {@code null} {@code BreakChecker} means no integration constrains mining — the mining mode
 * then attaches no future at all, so the common case allocates nothing.
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
   * @return a future of {@code true} if breaking is permitted
   */
  CompletableFuture<Boolean> breakable(A agent, Cell cell, MinecraftWorld world, MinecraftBlock block);
}
