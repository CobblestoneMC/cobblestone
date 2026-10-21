/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge.plugin;

import org.cobblestonemc.minecraft.MinecraftWorld;
import org.cobblestonemc.minecraft.regions.RegionStores;
import org.cobblestonemc.sponge.WorldWrapper;
import org.spongepowered.api.block.transaction.BlockTransactionReceipt;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.Order;
import org.spongepowered.api.event.block.ChangeBlockEvent;
import org.spongepowered.api.event.world.SaveWorldEvent;
import org.spongepowered.api.event.world.UnloadWorldEvent;
import org.spongepowered.math.vector.Vector3i;

/**
 * Keeps the regions honest: the Sponge counterpart of the Paper plugin's {@code
 * PaperRegionListener}.
 *
 * <p>Sponge reports every block change through one event, after it happened, so a single listener
 * covers what Paper needs a dozen for: each changed block forgets its chunk's regions, to be built
 * again when a search next needs them.
 */
final class SpongeRegionListener {

  private final RegionStores stores;
  private final WorldWrapper worlds;

  SpongeRegionListener(RegionStores stores, WorldWrapper worlds) {
    this.stores = stores;
    this.worlds = worlds;
  }

  /** Every block that changed forgets its chunk's regions. */
  @Listener(order = Order.POST)
  public void onChange(ChangeBlockEvent.Post event) {
    MinecraftWorld world = worlds.wrap(event.world());
    for (BlockTransactionReceipt receipt : event.receipts()) {
      Vector3i position = receipt.originalBlock().position();
      stores.invalidate(world, position.x() >> 4, position.z() >> 4);
    }
  }

  /** The world has saved: write the regions too, so a crash after it loses nothing. */
  @Listener(order = Order.POST)
  public void onSave(SaveWorldEvent.Post event) {
    stores.flush();
  }

  /** A world that unloads takes its regions with it, once they are written. */
  @Listener(order = Order.POST)
  public void onWorldUnload(UnloadWorldEvent event) {
    stores.worldUnloaded(worlds.wrap(event.world()).key());
  }
}
