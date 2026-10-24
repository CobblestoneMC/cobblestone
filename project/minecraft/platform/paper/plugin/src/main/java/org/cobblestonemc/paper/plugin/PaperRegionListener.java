/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper.plugin;

import java.util.List;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.FluidLevelChangeEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.block.SpongeAbsorbEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.event.world.WorldSaveEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.cobblestonemc.minecraft.regions.RegionStores;
import org.cobblestonemc.paper.PaperTerrain;
import org.cobblestonemc.paper.WorldWrapper;

/**
 * Keeps the regions honest.
 *
 * <p><b>Every way a block changes, if the change matters, forgets its chunk's regions.</b> A column
 * of regions is a fact about the blocks it was built from, and one built before a player dug a
 * tunnel would route the next search around a wall that is gone. Each event compares the block
 * before and after ({@link PaperTerrain#classifiesAlike}), so the changes that leave the terrain as
 * the regions see it — a crop growing, water finding its level, grass turning to dirt — cost
 * nothing; the rest are forgotten in batches by {@link RegionStores}. Changes no event reports,
 * from other plugins writing blocks directly, are not seen.
 *
 * <p>Block events run at {@link EventPriority#MONITOR} and ignore cancelled events: they react to
 * what happened, never to what was attempted.
 */
final class PaperRegionListener implements Listener {

  private final RegionStores stores;
  private final WorldWrapper worlds;
  private final BlockData air = Material.AIR.createBlockData();

  PaperRegionListener(RegionStores stores, WorldWrapper worlds) {
    this.stores = stores;
    this.worlds = worlds;
  }

  /** A block is becoming {@code after}. */
  private void changed(Block block, BlockData after) {
    if (!PaperTerrain.classifiesAlike(block.getBlockData(), after)) {
      forget(block);
    }
  }

  /** Blocks are becoming these states; the world still holds what they were. */
  private void changed(List<BlockState> states) {
    for (BlockState state : states) {
      changed(state.getBlock(), state.getBlockData());
    }
  }

  /** Blocks are being removed, by fire, decay or explosion. */
  private void removed(List<Block> blocks) {
    for (Block block : blocks) {
      changed(block, air);
    }
  }

  private void forget(Block block) {
    stores.invalidate(worlds.wrap(block.getWorld()), block.getX() >> 4, block.getZ() >> 4);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onBreak(BlockBreakEvent event) {
    changed(event.getBlock(), air);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onPlace(BlockPlaceEvent event) {
    if (!PaperTerrain.classifiesAlike(
        event.getBlockReplacedState().getBlockData(), event.getBlockPlaced().getBlockData())) {
      forget(event.getBlock());
    }
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onBurn(BlockBurnEvent event) {
    changed(event.getBlock(), air);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onBlockExplode(BlockExplodeEvent event) {
    changed(event.getBlock(), air);
    removed(event.blockList());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onEntityExplode(EntityExplodeEvent event) {
    removed(event.blockList());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onFade(BlockFadeEvent event) {
    changed(event.getBlock(), event.getNewState().getBlockData());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onForm(BlockFormEvent event) {
    changed(event.getBlock(), event.getNewState().getBlockData());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onGrow(BlockGrowEvent event) {
    changed(event.getBlock(), event.getNewState().getBlockData());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onFertilize(BlockFertilizeEvent event) {
    changed(event.getBlocks());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onStructureGrow(StructureGrowEvent event) {
    changed(event.getBlocks());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onFlow(BlockFromToEvent event) {
    Block from = event.getBlock();
    changed(event.getToBlock(), from.getBlockData());
    if (!from.isLiquid()) {
      forget(from); // a dragon egg teleporting: it leaves where it was, too
    }
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onFluidLevel(FluidLevelChangeEvent event) {
    changed(event.getBlock(), event.getNewData());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onDecay(LeavesDecayEvent event) {
    changed(event.getBlock(), air);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onSponge(SpongeAbsorbEvent event) {
    changed(event.getBlocks()); // the water it soaks up; the sponge itself only gets wet
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onEntityChange(EntityChangeBlockEvent event) {
    changed(event.getBlock(), event.getBlockData());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onPistonExtend(BlockPistonExtendEvent event) {
    pushed(event.getBlock(), event.getBlocks(), event.getDirection());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onPistonRetract(BlockPistonRetractEvent event) {
    pushed(event.getBlock(), event.getBlocks(), event.getDirection());
  }

  /** A piston moves blocks one step: both where they were and where they land change. */
  private void pushed(Block piston, List<Block> moved, BlockFace direction) {
    forget(piston);
    forget(piston.getRelative(direction));
    for (Block block : moved) {
      forget(block);
      forget(block.getRelative(direction));
    }
  }

  /** The world is saving: write the regions too, so a crash after it loses nothing. */
  @EventHandler(priority = EventPriority.MONITOR)
  public void onSave(WorldSaveEvent event) {
    stores.flush();
  }

  /** A world that unloads takes its regions with it, once they are written. */
  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onWorldUnload(WorldUnloadEvent event) {
    stores.worldUnloaded(worlds.wrap(event.getWorld()).key());
  }
}
