/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper.plugin;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.plugin.Plugin;
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
 *
 * <p>A cached chunk is dropped twice, now and again next tick. Most of these events fire
 * <i>before</i> the world is written — {@link BlockBreakEvent}, the explosions, the pistons, the
 * bucket events — so a search re-reading the chunk in the same tick would re-cache the block as it
 * still stands, and nothing further would invalidate it. The second pass runs once the change has
 * landed. It is only queued for a chunk that <i>was</i> cached, since the scheduled task is the
 * expensive half and a chunk nothing has cached has no search walking through it.
 *
 * <p><b>Only changes that can alter a route are listened for.</b> Every handler here runs on the
 * thread owning the block, on a server where farms, fluids and falling sand fire block events by
 * the hundred per tick, so the list is deliberately short. Crops are passable before and after they
 * grow, leaves are solid before and after they decay, and a tree filling in blocks nothing that was
 * walkable — so growth and decay are not listened for at all. Ice forming and melting is, because
 * it makes and unmakes bridges.
 */
final class BlockChangeListener implements Listener {

  private final Plugin plugin;
  private final PaperNavigationServiceImpl navigation;

  BlockChangeListener(Plugin plugin, PaperNavigationServiceImpl navigation) {
    this.plugin = plugin;
    this.navigation = navigation;
  }

  private void changed(Block block) {
    invalidate(block.getWorld(), Set.of(chunkOf(block.getX(), block.getZ())));
  }

  private void changed(Collection<Block> blocks) {
    if (blocks.isEmpty()) {
      return;
    }
    Set<Long> chunks = new HashSet<>();
    for (Block block : blocks) {
      chunks.add(chunkOf(block.getX(), block.getZ()));
    }
    invalidate(blocks.iterator().next().getWorld(), chunks);
  }

  /** Drops each chunk's snapshot now, and again once the tick's block writes have landed. */
  private void invalidate(World world, Set<Long> chunks) {
    String worldKey = world.getKey().asString();
    for (long packed : chunks) {
      int chunkX = (int) (packed >> 32);
      int chunkZ = (int) packed;
      if (!navigation.invalidate(worldKey, chunkX, chunkZ)) {
        continue; // nothing was cached here, so nothing can be re-cached stale a moment from now
      }
      Bukkit.getRegionScheduler()
          .runDelayed(
              plugin,
              world,
              chunkX,
              chunkZ,
              ignored -> navigation.invalidate(worldKey, chunkX, chunkZ),
              1L);
    }
  }

  /** Packs a block position's chunk coordinates into one long, so grouping allocates no key. */
  private static long chunkOf(int blockX, int blockZ) {
    return ((long) (blockX >> 4) << 32) ^ ((blockZ >> 4) & 0xffffffffL);
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

  /** Ice melting, mostly: an ice bridge that vanishes leaves a route walking onto open water. */
  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  void onFade(BlockFadeEvent event) {
    changed(event.getBlock());
  }

  /** Ice forming, mostly: a frozen ocean is walkable where open water was not. */
  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  void onForm(BlockFormEvent event) {
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
    changed(event.blockList());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  void onEntityExplode(EntityExplodeEvent event) {
    changed(event.blockList());
  }

  // Pistons move a run of blocks, and the destination of the last one is a block beyond the run —
  // so the moved blocks' own chunks are not always all of the chunks that change. Invalidating both
  // the piston and every block it shifts covers it.
  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  void onPistonExtend(BlockPistonExtendEvent event) {
    pistonMoved(event.getBlock(), event.getBlocks(), event.getDirection());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  void onPistonRetract(BlockPistonRetractEvent event) {
    pistonMoved(event.getBlock(), event.getBlocks(), event.getDirection());
  }

  private void pistonMoved(Block piston, Collection<Block> moved, BlockFace face) {
    Set<Long> chunks = new HashSet<>();
    chunks.add(chunkOf(piston.getX(), piston.getZ()));
    for (Block block : moved) {
      chunks.add(chunkOf(block.getX(), block.getZ()));
      Block destination = block.getRelative(face);
      chunks.add(chunkOf(destination.getX(), destination.getZ()));
    }
    invalidate(piston.getWorld(), chunks);
  }
}
