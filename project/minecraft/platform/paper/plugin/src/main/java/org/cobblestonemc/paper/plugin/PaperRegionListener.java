/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper.plugin;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
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
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.event.world.WorldSaveEvent;
import org.cobblestonemc.minecraft.MinecraftChunk;
import org.cobblestonemc.minecraft.MinecraftWorld;
import org.cobblestonemc.minecraft.lod.RegionStores;
import org.cobblestonemc.paper.WorldWrapper;

/**
 * Keeps the region summaries honest, and gets them made early.
 *
 * <p><b>Every way a block changes forgets its chunk's summary.</b> A summary is a fact about the
 * blocks it was made from, and one made before a player dug a tunnel would route the next search
 * around a wall that is gone. Forgetting is cheap — a map removal — so it is done for every event
 * that changes a block, not only the common ones; the summary is made again when a search next
 * needs it. Changes no event reports, from other plugins writing blocks directly or from editing
 * the world while the server is stopped, are not seen.
 *
 * <p><b>A chunk that loads is summarized in the background,</b> when the server is configured to,
 * so terrain players walk through is ready before anyone navigates it. Only chunks not already
 * known, and only while the background queue has room.
 *
 * <p>Everything here runs at {@link EventPriority#MONITOR} and ignores cancelled events: it reacts
 * to what happened, never to what was attempted.
 */
final class PaperRegionListener implements Listener {

  private final RegionStores stores;
  private final WorldWrapper worlds;
  private final boolean buildOnLoad;
  private final LoadedChunks chunks;

  PaperRegionListener(
      RegionStores stores, WorldWrapper worlds, LoadedChunks chunks, boolean buildOnLoad) {
    this.stores = stores;
    this.worlds = worlds;
    this.buildOnLoad = buildOnLoad;
    this.chunks = chunks;
  }

  private void changed(Block block) {
    stores.invalidate(worlds.wrap(block.getWorld()), block.getX() >> 4, block.getZ() >> 4);
  }

  private void changed(List<Block> blocks) {
    for (Block block : blocks) {
      changed(block);
    }
  }

  private void changedStates(List<BlockState> states) {
    for (BlockState state : states) {
      stores.invalidate(worlds.wrap(state.getWorld()), state.getX() >> 4, state.getZ() >> 4);
    }
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onBreak(BlockBreakEvent event) {
    changed(event.getBlock());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onPlace(BlockPlaceEvent event) {
    changed(event.getBlock());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onBurn(BlockBurnEvent event) {
    changed(event.getBlock());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onBlockExplode(BlockExplodeEvent event) {
    changed(event.getBlock());
    changed(event.blockList());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onEntityExplode(EntityExplodeEvent event) {
    changed(event.blockList());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onFade(BlockFadeEvent event) {
    changed(event.getBlock());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onForm(BlockFormEvent event) {
    changed(event.getBlock());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onGrow(BlockGrowEvent event) {
    changed(event.getBlock());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onFertilize(BlockFertilizeEvent event) {
    changedStates(event.getBlocks());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onStructureGrow(StructureGrowEvent event) {
    changedStates(event.getBlocks());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onFlow(BlockFromToEvent event) {
    changed(event.getToBlock());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onFluidLevel(FluidLevelChangeEvent event) {
    changed(event.getBlock());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onDecay(LeavesDecayEvent event) {
    changed(event.getBlock());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onSponge(SpongeAbsorbEvent event) {
    changed(event.getBlock());
    changedStates(event.getBlocks());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onEntityChange(EntityChangeBlockEvent event) {
    changed(event.getBlock());
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
    changed(piston);
    changed(piston.getRelative(direction));
    for (Block block : moved) {
      changed(block);
      changed(block.getRelative(direction));
    }
  }

  /** The world is saving: write the summaries too, so a crash after it loses nothing. */
  @EventHandler(priority = EventPriority.MONITOR)
  public void onSave(WorldSaveEvent event) {
    stores.flush();
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onChunkLoad(ChunkLoadEvent event) {
    if (!buildOnLoad || !stores.prebuildRoom()) {
      return;
    }
    Chunk chunk = event.getChunk();
    MinecraftWorld world = worlds.wrap(chunk.getWorld());
    if (stores.known(world, chunk.getX(), chunk.getZ())) {
      return;
    }
    // Read the way searches read: the chunk is loaded, so this is a copy, not a load, and the
    // copy and the build both happen off the server thread.
    chunks
        .readLoaded(chunk.getWorld(), chunk.getX(), chunk.getZ())
        .thenAccept(
            read -> {
              if (read != null) {
                stores.prebuild(world, chunk.getX(), chunk.getZ(), read);
              }
            });
  }

  /** Reads a chunk the server has loaded, without loading anything. */
  interface LoadedChunks {
    CompletableFuture<MinecraftChunk> readLoaded(World world, int chunkX, int chunkZ);
  }
}
