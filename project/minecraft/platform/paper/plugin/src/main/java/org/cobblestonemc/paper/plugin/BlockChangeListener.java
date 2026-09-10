/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper.plugin;

import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.cobblestonemc.paper.PaperNavigationServiceImpl;

/**
 * Drops a cached chunk snapshot whenever a block in it actually changes.
 *
 * <p>The chunk cache otherwise only checks freshness when it takes a new entry, and the chunks a
 * search keeps touching are never the least-recently-used ones — so without this a wall built or a
 * door broken today would keep routing players through yesterday's terrain until the server
 * restarted.
 *
 * <p>Handlers run at {@link EventPriority#MONITOR} and ignore cancelled events, so a change another
 * plugin vetoes never evicts anything. Invalidation is cheap (one map removal) and only ever costs
 * a re-read later, so it is better to drop a chunk needlessly than to miss a change: events that
 * move many blocks at once simply invalidate every chunk they touch.
 */
final class BlockChangeListener implements Listener {

  private final PaperNavigationServiceImpl navigation;

  BlockChangeListener(PaperNavigationServiceImpl navigation) {
    this.navigation = navigation;
  }

  private void changed(Block block) {
    navigation.invalidateBlock(block.getWorld().getKey().asString(), block.getX(), block.getZ());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  void onPlace(BlockPlaceEvent event) {
    changed(event.getBlock());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  void onBreak(BlockBreakEvent event) {
    changed(event.getBlock());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  void onBurn(BlockBurnEvent event) {
    changed(event.getBlock());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  void onFade(BlockFadeEvent event) {
    changed(event.getBlock());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  void onForm(BlockFormEvent event) {
    changed(event.getBlock());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  void onGrow(BlockGrowEvent event) {
    changed(event.getBlock());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  void onLeavesDecay(LeavesDecayEvent event) {
    changed(event.getBlock());
  }

  /** Falling sand, endermen, and anything else that rewrites a block by entity action. */
  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  void onEntityChangeBlock(EntityChangeBlockEvent event) {
    changed(event.getBlock());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  void onBucketEmpty(PlayerBucketEmptyEvent event) {
    changed(event.getBlock());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  void onBucketFill(PlayerBucketFillEvent event) {
    changed(event.getBlock());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  void onBlockExplode(BlockExplodeEvent event) {
    changed(event.getBlock());
    event.blockList().forEach(this::changed);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  void onEntityExplode(EntityExplodeEvent event) {
    event.blockList().forEach(this::changed);
  }

  /** A tree or mushroom filling in: every block of the structure is new terrain. */
  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  void onStructureGrow(StructureGrowEvent event) {
    for (BlockState state : event.getBlocks()) {
      navigation.invalidateBlock(state.getWorld().getKey().asString(), state.getX(), state.getZ());
    }
  }

  // Pistons move a run of blocks, and the destination of the last one is a block beyond the run —
  // so the moved blocks' own chunks are not always all of the chunks that change. Invalidating both
  // the piston and every block it shifts covers it.
  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  void onPistonExtend(BlockPistonExtendEvent event) {
    changed(event.getBlock());
    event.getBlocks().forEach(this::changed);
    event.getBlocks().forEach(block -> changed(block.getRelative(event.getDirection())));
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  void onPistonRetract(BlockPistonRetractEvent event) {
    changed(event.getBlock());
    event.getBlocks().forEach(this::changed);
    event.getBlocks().forEach(block -> changed(block.getRelative(event.getDirection())));
  }
}
