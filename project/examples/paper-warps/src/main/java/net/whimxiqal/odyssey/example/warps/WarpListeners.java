/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.example.warps;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.joml.Vector3i;

/**
 * The two runtime behaviours of the example: the wooden-shovel <b>wand</b> that builds a portal box
 * (left-click = corner 1, right-click = corner 2), and the <b>auto-teleport</b> that fires when a
 * player walks into a portal box.
 */
final class WarpListeners implements Listener {

  /** The block-selection wand for defining portal entrance boxes. */
  static final Material WAND = Material.WOODEN_SHOVEL;

  private final Plugin plugin;
  private final WarpStore store;
  private final Selections selections;

  WarpListeners(Plugin plugin, WarpStore store, Selections selections) {
    this.plugin = plugin;
    this.store = store;
    this.selections = selections;
  }

  @EventHandler
  public void onWand(PlayerInteractEvent event) {
    if (event.getItem() == null || event.getItem().getType() != WAND) {
      return;
    }
    Block block = event.getClickedBlock();
    if (block == null) {
      return; // clicked air; need a block to select a corner
    }
    Player player = event.getPlayer();
    String world = block.getWorld().getKey().asString();
    Vector3i cell = new Vector3i(block.getX(), block.getY(), block.getZ());
    if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
      event.setCancelled(true); // don't start breaking the block
      selections.setCorner1(player.getUniqueId(), world, cell);
      player.sendMessage(
          Component.text("Corner 1 set to " + describe(cell) + ".", NamedTextColor.YELLOW));
    } else if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
      event.setCancelled(true);
      selections.setCorner2(player.getUniqueId(), world, cell);
      player.sendMessage(
          Component.text("Corner 2 set to " + describe(cell) + ".", NamedTextColor.YELLOW));
    }
  }

  @EventHandler(ignoreCancelled = true)
  public void onMove(PlayerMoveEvent event) {
    Location to = event.getTo();
    Location from = event.getFrom();
    if (to.getBlockX() == from.getBlockX()
        && to.getBlockY() == from.getBlockY()
        && to.getBlockZ() == from.getBlockZ()) {
      return; // only check on block changes, not every sub-block wiggle
    }
    String world = to.getWorld().getKey().asString();
    for (Portal portal : store.portals()) {
      if (!portal.contains(world, to.getBlockX(), to.getBlockY(), to.getBlockZ())) {
        continue;
      }
      store
          .getDestination(portal.destination())
          .ifPresent(
              destination -> {
                World destWorld = Worlds.byKey(destination.world());
                if (destWorld != null) {
                  // teleportAsync is Folia-safe: it hops to the destination's region thread for us.
                  event.setTo(destination.toLocation(destWorld));
                  event
                      .getPlayer()
                      .sendMessage(
                          Component.text(
                              "Warped through '" + portal.name() + "'.", NamedTextColor.AQUA));
                }
              });
      return; // one portal per move is plenty
    }
  }

  @EventHandler
  public void onQuit(PlayerQuitEvent event) {
    selections.clear(event.getPlayer().getUniqueId());
  }

  private static String describe(Vector3i cell) {
    return cell.x() + ", " + cell.y() + ", " + cell.z();
  }
}
