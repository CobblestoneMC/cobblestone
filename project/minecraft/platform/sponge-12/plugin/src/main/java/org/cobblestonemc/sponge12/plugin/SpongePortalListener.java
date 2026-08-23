/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge12.plugin;

import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import org.cobblestonemc.CobblestoneLogger;
import org.cobblestonemc.minecraft.MinecraftScheduler;
import org.cobblestonemc.plugin.data.EndReturnPortal;
import org.cobblestonemc.plugin.data.EndReturnPortalDao;
import org.cobblestonemc.plugin.data.GatewayDao;
import org.cobblestonemc.plugin.data.GatewayTransition;
import org.cobblestonemc.plugin.data.PortalRegion;
import org.cobblestonemc.plugin.data.PortalTransition;
import org.cobblestonemc.plugin.data.PortalTransitionDao;
import org.spongepowered.api.block.BlockType;
import org.spongepowered.api.block.BlockTypes;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import org.spongepowered.api.event.EventContextKeys;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.cause.entity.MovementType;
import org.spongepowered.api.event.cause.entity.MovementTypes;
import org.spongepowered.api.event.entity.ChangeEntityWorldEvent;
import org.spongepowered.api.event.entity.MoveEntityEvent;
import org.spongepowered.api.world.WorldTypes;
import org.spongepowered.api.world.server.ServerWorld;
import org.spongepowered.math.vector.Vector3d;

/**
 * Learns vanilla portal links empirically from Sponge's teleport events, keyed by the {@link
 * MovementType} in the event context.
 *
 * <ul>
 *   <li><b>{@link ChangeEntityWorldEvent.Reposition}</b> (a cross-world move with positions) with
 *       {@link MovementTypes#PORTAL} covers nether portals (both directions, upserted by source),
 *       overworld&nbsp;&rarr;&nbsp;End (region&nbsp;&rarr;&nbsp;point), and End&nbsp;&rarr;&nbsp;
 *       overworld (region only; destination resolved per-player at search time).
 *   <li><b>{@link MoveEntityEvent}</b> with {@link MovementTypes#END_GATEWAY} (a same-world jump)
 *       caches the gateway block &rarr; its resolved exit.
 * </ul>
 *
 * <p>Block reads happen on the server thread (in the event); persistence runs off-thread.
 */
final class SpongePortalListener {

  private final PortalTransitionDao portals;
  private final EndReturnPortalDao endReturns;
  private final GatewayDao gateways;
  private final MinecraftScheduler<?> scheduler;
  private final CobblestoneLogger logger;
  private final DoubleSupplier cost;
  private final BooleanSupplier enabled;

  SpongePortalListener(
      PortalTransitionDao portals,
      EndReturnPortalDao endReturns,
      GatewayDao gateways,
      MinecraftScheduler<?> scheduler,
      CobblestoneLogger logger,
      DoubleSupplier cost,
      BooleanSupplier enabled) {
    this.portals = portals;
    this.endReturns = endReturns;
    this.gateways = gateways;
    this.scheduler = scheduler;
    this.logger = logger;
    this.cost = cost;
    this.enabled = enabled;
  }

  /** Cross-world portal teleports: nether (both ways) and the End (forward and return). */
  @Listener
  public void onWorldChange(ChangeEntityWorldEvent.Reposition event) {
    if (!enabled.getAsBoolean() || !(event.entity() instanceof ServerPlayer)) {
      return;
    }
    if (!isMovement(event.context().get(EventContextKeys.MOVEMENT_TYPE), MovementTypes.PORTAL)) {
      return;
    }
    ServerWorld fromWorld = event.originalWorld();
    ServerWorld toWorld = event.destinationWorld();
    Vector3d from = event.originalPosition();
    Vector3d to = event.destinationPosition();
    if (isNether(fromWorld) || isNether(toWorld)) {
      recordNether(fromWorld, from, toWorld, to);
    } else if (isEnd(toWorld)) {
      recordEndForward(fromWorld, from, toWorld, to);
    } else if (isEnd(fromWorld)) {
      recordEndReturn(fromWorld, from);
    }
  }

  /** Same-world end-gateway jumps. */
  @Listener
  public void onMove(MoveEntityEvent event) {
    if (!enabled.getAsBoolean()
        || event instanceof ChangeEntityWorldEvent
        || !(event.entity() instanceof ServerPlayer player)) {
      return;
    }
    if (!isMovement(
        event.context().get(EventContextKeys.MOVEMENT_TYPE), MovementTypes.END_GATEWAY)) {
      return;
    }
    recordGateway(player.world(), event.originalPosition(), event.destinationPosition());
  }

  /** Upserts a source portal &rarr; destination-portal-centre link, keyed by the source portal. */
  private void recordNether(
      ServerWorld fromWorld, Vector3d from, ServerWorld toWorld, Vector3d to) {
    PortalRegion source = scan(fromWorld, from, BlockTypes.NETHER_PORTAL.get());
    PortalRegion dest = scan(toWorld, to, BlockTypes.NETHER_PORTAL.get());
    logger.debug("Discovered nether portal link {} -> {}", fromWorld.key(), toWorld.key());
    PortalTransition transition = transitionTo(source, dest.world(), centerPoint(dest));
    scheduler.runAsync(() -> portals.upsert(transition));
  }

  /** Records the overworld &rarr; End portal as a region &rarr; point link. */
  private void recordEndForward(
      ServerWorld fromWorld, Vector3d from, ServerWorld toWorld, Vector3d to) {
    PortalRegion source = scan(fromWorld, from, BlockTypes.END_PORTAL.get());
    PortalTransition transition =
        transitionTo(
            source,
            toWorld.key().asString(),
            new int[] {floor(to.x()), floor(to.y()), floor(to.z())});
    scheduler.runAsync(() -> portals.upsert(transition));
  }

  /**
   * Caches an End-return portal's region; its destination is the player's respawn at search time.
   */
  private void recordEndReturn(ServerWorld fromWorld, Vector3d from) {
    PortalRegion region = scan(fromWorld, from, BlockTypes.END_PORTAL.get());
    EndReturnPortal portal = new EndReturnPortal(region, cost.getAsDouble());
    scheduler.runAsync(() -> endReturns.upsert(portal));
  }

  /** Caches an end-gateway's resolved exit, keyed by the gateway block. */
  private void recordGateway(ServerWorld world, Vector3d from, Vector3d to) {
    PortalRegion box = scan(world, from, BlockTypes.END_GATEWAY.get());
    GatewayTransition gateway =
        new GatewayTransition(
            box.world(),
            box.minX(),
            box.minY(),
            box.minZ(),
            world.key().asString(),
            floor(to.x()),
            floor(to.y()),
            floor(to.z()),
            cost.getAsDouble());
    scheduler.runAsync(() -> gateways.upsert(gateway));
  }

  private static PortalRegion scan(ServerWorld world, Vector3d position, BlockType material) {
    return SpongePortals.scanPortal(
        world, floor(position.x()), floor(position.y()), floor(position.z()), material);
  }

  /** Builds a region &rarr; point transition from a source region to an arrival block. */
  private PortalTransition transitionTo(PortalRegion source, String toWorld, int[] to) {
    return new PortalTransition(
        source.world(),
        source.minX(),
        source.minY(),
        source.minZ(),
        source.maxX(),
        source.maxY(),
        source.maxZ(),
        toWorld,
        to[0],
        to[1],
        to[2],
        cost.getAsDouble());
  }

  /** The destination portal's horizontal centre at ground level, as a block coordinate. */
  private static int[] centerPoint(PortalRegion portal) {
    return new int[] {
      (int) Math.floor(portal.centerX()), portal.groundY(), (int) Math.floor(portal.centerZ())
    };
  }

  private static boolean isMovement(
      Optional<MovementType> actual,
      org.spongepowered.api.registry.DefaultedRegistryReference<MovementType> expected) {
    return actual.isPresent() && actual.get().equals(expected.get());
  }

  private static boolean isNether(ServerWorld world) {
    return world.worldType().equals(WorldTypes.THE_NETHER.get());
  }

  private static boolean isEnd(ServerWorld world) {
    return world.worldType().equals(WorldTypes.THE_END.get());
  }

  private static int floor(double value) {
    return (int) Math.floor(value);
  }
}
