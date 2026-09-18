/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge.plugin;

import org.cobblestonemc.sponge.SpongeNavigationServiceImpl;
import org.spongepowered.api.block.BlockSnapshot;
import org.spongepowered.api.block.transaction.BlockTransactionReceipt;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.block.ChangeBlockEvent;
import org.spongepowered.math.vector.Vector3i;

/**
 * Drops a cached chunk snapshot whenever a block in it actually changes.
 *
 * <p>The chunk cache otherwise only checks freshness when it takes a new entry, and the chunks a
 * search keeps touching are never the least-recently-used ones — so without this a wall built or a
 * door broken today would keep routing players through yesterday's terrain until the server
 * restarted.
 *
 * <p>Sponge funnels every block change — placement, breakage, explosions, growth, pistons, fluids —
 * through one event, so one handler covers what Paper needs a dozen for. It listens on {@link
 * ChangeBlockEvent.Post}, which fires after the change has been written and is not cancellable: the
 * cancellable {@link ChangeBlockEvent.All} fires <i>before</i> the world is modified, so evicting
 * there would let a search re-read the chunk and cache the block as it still stands, with nothing
 * left to invalidate it afterwards. Invalidation only ever costs a re-read later, so dropping a
 * chunk needlessly is the safe direction to err.
 */
final class SpongeBlockChangeListener {

  private final SpongeNavigationServiceImpl navigation;

  SpongeBlockChangeListener(SpongeNavigationServiceImpl navigation) {
    this.navigation = navigation;
  }

  @Listener
  public void onChangeBlock(ChangeBlockEvent.Post event) {
    for (BlockTransactionReceipt receipt : event.receipts()) {
      BlockSnapshot changed = receipt.finalBlock();
      Vector3i position = changed.position();
      navigation.invalidateBlock(changed.world().asString(), position.x(), position.z());
    }
  }
}
