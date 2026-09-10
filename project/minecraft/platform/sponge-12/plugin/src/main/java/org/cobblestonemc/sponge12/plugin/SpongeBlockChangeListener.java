/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge12.plugin;

import org.cobblestonemc.sponge12.SpongeNavigationServiceImpl;
import org.spongepowered.api.block.BlockSnapshot;
import org.spongepowered.api.block.transaction.BlockTransaction;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.Order;
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
 * through {@link ChangeBlockEvent.All}, so one handler covers what Paper needs a dozen events for.
 * It runs {@link Order#POST} so a change another plugin vetoes never evicts anything. Invalidation
 * only ever costs a re-read later, so dropping a chunk needlessly is the safe direction to err.
 */
final class SpongeBlockChangeListener {

  private final SpongeNavigationServiceImpl navigation;

  SpongeBlockChangeListener(SpongeNavigationServiceImpl navigation) {
    this.navigation = navigation;
  }

  @Listener(order = Order.POST)
  public void onChangeBlock(ChangeBlockEvent.All event) {
    for (BlockTransaction transaction : event.transactions()) {
      BlockSnapshot original = transaction.original();
      Vector3i position = original.position();
      navigation.invalidateBlock(original.world().asString(), position.x(), position.z());
    }
  }
}
