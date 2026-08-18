/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.sponge12;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.whimxiqal.odyssey.CellRegion;
import net.whimxiqal.odyssey.DomainRegion;
import net.whimxiqal.odyssey.FutureOr;
import net.whimxiqal.odyssey.HeuristicStrategy;
import net.whimxiqal.odyssey.Heuristics;
import net.whimxiqal.odyssey.ModesProvider;
import net.whimxiqal.odyssey.OdysseyApi;
import net.whimxiqal.odyssey.OdysseyLogger;
import net.whimxiqal.odyssey.Position;
import net.whimxiqal.odyssey.Restriction;
import net.whimxiqal.odyssey.SingleDestination;
import net.whimxiqal.odyssey.api.Destination;
import net.whimxiqal.odyssey.api.SearchHandle;
import net.whimxiqal.odyssey.minecraft.ChunkProvider;
import net.whimxiqal.odyssey.minecraft.ChunkProviderSettings;
import net.whimxiqal.odyssey.minecraft.MinecraftScheduler;
import net.whimxiqal.odyssey.minecraft.MinecraftWorld;
import net.whimxiqal.odyssey.minecraft.OdysseyPlayer;
import net.whimxiqal.odyssey.minecraft.api.MinecraftSearchSettings;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepPayload;
import net.whimxiqal.odyssey.minecraft.api.WorldRegion;
import net.whimxiqal.odyssey.minecraft.modes.MinecraftModes;
import net.whimxiqal.odyssey.minecraft.registry.OwnedRegistry;
import net.whimxiqal.odyssey.sponge12.api.BoxWorldRegion;
import net.whimxiqal.odyssey.sponge12.api.BreakChecker;
import net.whimxiqal.odyssey.sponge12.api.NavigationService;
import net.whimxiqal.odyssey.sponge12.api.PassChecker;
import net.whimxiqal.odyssey.sponge12.api.SearchModificationRegistrar;
import net.whimxiqal.odyssey.sponge12.api.SearchModificationService;
import net.whimxiqal.odyssey.sponge12.api.Transition;
import org.spongepowered.api.ResourceKey;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.block.BlockState;
import org.spongepowered.api.entity.Entity;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import org.spongepowered.api.item.ItemTypes;
import org.spongepowered.api.item.inventory.ItemStack;
import org.spongepowered.api.item.inventory.Slot;
import org.spongepowered.api.world.server.ServerLocation;
import org.spongepowered.api.world.server.ServerWorld;
import org.spongepowered.math.vector.Vector3i;
import org.spongepowered.plugin.PluginContainer;

/**
 * The Sponge implementation of Odyssey's {@link NavigationService}, doubling as the {@link
 * SearchModificationRegistrar} (Odyssey owns the registry; Sponge has no service manager) and the
 * internal {@link WorldWrapper}. Mirrors the Paper implementation, in Sponge's native types.
 */
public final class SpongeNavigationServiceImpl
    implements NavigationService, SearchModificationRegistrar, WorldWrapper {

  // The true global-minimum per-block cost (flying, MovementCosts.FLY = 0.08). Used as the
  // admissible Tier-1 bound and the running-average's cold-start estimate.
  private static final double CHEAPEST_COST_PER_BLOCK = 0.08;

  private final OdysseyLogger logger;
  private final SpongeScheduler scheduler;
  private final ChunkProvider chunkProvider;
  private final OdysseyApi core;
  private final HeuristicStrategy heuristic;
  private final Map<String, MinecraftWorld> worldCache = new ConcurrentHashMap<>();
  private final OwnedRegistry<SearchModificationService> searchModifiers = new OwnedRegistry<>();

  /**
   * Creates the API for a plugin.
   *
   * @param plugin the owning plugin container
   * @param logger the logger for diagnostics
   */
  public SpongeNavigationServiceImpl(PluginContainer plugin, OdysseyLogger logger) {
    this.logger = logger;
    int workerThreads = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
    this.scheduler = new SpongeScheduler(plugin, workerThreads);
    SpongePlatformApi platform = new SpongePlatformApi(plugin, scheduler);
    this.chunkProvider = new ChunkProvider(platform, ChunkProviderSettings.defaults());
    this.core = OdysseyApi.load();
    this.heuristic = Heuristics.runningAverage(CHEAPEST_COST_PER_BLOCK);
  }

  @Override
  public SearchHandle<ServerLocation, MinecraftStepPayload> navigatePlayer(
      ServerPlayer player, ServerLocation destination, MinecraftSearchSettings settings) {
    DomainRegion<MinecraftWorld> region =
        new CellRegion<>(SpongeConversions.cell(destination), wrap(destination.world()));
    return search(player, new SingleDestination<>(region), settings);
  }

  @Override
  public SearchHandle<ServerLocation, MinecraftStepPayload> navigatePlayerToRegion(
      ServerPlayer player,
      ServerLocation location1,
      ServerLocation location2,
      MinecraftSearchSettings settings) {
    BoxWorldRegion region = BoxWorldRegion.of(location1, location2);
    return search(
        player, new SingleDestination<>(SpongeConversions.region(region, this)), settings);
  }

  /**
   * Begins a search toward a plugin-provided {@link Destination} (e.g. a resolved waypoint), which
   * may span several regions/endpoints. Used by the {@code /navigate} command; not on the public
   * native façade because it speaks Sponge's {@link WorldRegion} type.
   *
   * @param player the navigating player
   * @param destination the goal, as one or more world regions
   * @param settings the search limits and knobs
   * @return a handle to the in-flight search, yielding native-located steps
   */
  public SearchHandle<ServerLocation, MinecraftStepPayload> navigatePlayerToDestination(
      ServerPlayer player,
      Destination<WorldRegion<ServerWorld, Vector3i>> destination,
      MinecraftSearchSettings settings) {
    List<DomainRegion<MinecraftWorld>> regions = new ArrayList<>();
    for (WorldRegion<ServerWorld, Vector3i> region : destination.regions()) {
      if (region.world() == null) {
        continue; // world unloaded or target gone; skip rather than NPE
      }
      regions.add(SpongeConversions.region(region, this));
    }
    return search(player, () -> regions, settings);
  }

  /**
   * Converts a native {@link ServerLocation} to a core {@link Position}, for scheduling a trip on
   * the server thread.
   *
   * @param location the location
   * @return the position
   */
  public Position<MinecraftWorld> position(ServerLocation location) {
    return new Position<>(SpongeConversions.cell(location), wrap(location.world()));
  }

  /**
   * Returns the platform scheduler, so the plugin can tick trips on it.
   *
   * @return the scheduler
   */
  public MinecraftScheduler<Entity> scheduler() {
    return scheduler;
  }

  /** Stops the search worker pool; call on plugin disable. */
  public void shutdown() {
    scheduler.shutdown();
  }

  @Override
  public void register(PluginContainer owner, SearchModificationService service) {
    searchModifiers.register(owner.metadata().id(), service);
  }

  /**
   * Drops every search modifier a departing owner registered (called when that plugin stops).
   *
   * @param owner the departing owner's id
   * @return how many modifiers were removed
   */
  public int purgeOwner(String owner) {
    return searchModifiers.purge(owner);
  }

  private SearchHandle<ServerLocation, MinecraftStepPayload> search(
      ServerPlayer player,
      Destination<DomainRegion<MinecraftWorld>> destination,
      MinecraftSearchSettings settings) {
    OdysseyPlayer agent = new SpongePlayer(player);
    ServerLocation origin = player.serverLocation();
    Position<MinecraftWorld> originPosition =
        new Position<>(SpongeConversions.cell(origin), wrap(origin.world()));
    // One snapshot of the registered modifiers drives all three influences on this search.
    List<SearchModificationService> modifiers = searchModifiers.values();
    net.whimxiqal.odyssey.minecraft.BreakChecker<OdysseyPlayer> breakChecker =
        buildBreakChecker(modifiers, player);
    List<Restriction<OdysseyPlayer, MinecraftWorld>> restrictions =
        buildRestrictions(modifiers, player);
    // Read the pearl count now, on the server thread; the provider closes over it and is invoked
    // per
    // leg on worker threads, where it must not touch the Sponge player.
    ModesProvider<OdysseyPlayer, MinecraftStepPayload, MinecraftWorld> modes =
        MinecraftModes.providerFor(
            agent, settings.excludedModes(), breakChecker, countEnderPearls(player));

    CompletableFuture<SearchHandle<Position<MinecraftWorld>, MinecraftStepPayload>> handleFuture =
        gatherTransitions(
                modifiers, player, settings.excludedWorlds(), settings.excludedDimensions())
            .thenApply(
                gathered ->
                    core.navigate(
                        logger,
                        scheduler,
                        agent,
                        originPosition,
                        destination,
                        modes,
                        gathered,
                        restrictions,
                        heuristic,
                        settings.settings()));
    return new SpongeSearchHandle(handleFuture);
  }

  private CompletableFuture<
          List<net.whimxiqal.odyssey.Transition<MinecraftStepPayload, MinecraftWorld>>>
      gatherTransitions(
          List<SearchModificationService> modifiers,
          ServerPlayer player,
          Set<String> excludedWorlds,
          Set<String> excludedDimensions) {
    if (modifiers.isEmpty()) {
      return CompletableFuture.completedFuture(List.of());
    }
    List<CompletableFuture<List<Transition>>> futures = new ArrayList<>();
    for (SearchModificationService modifier : modifiers) {
      futures.add(modifier.computeTransitions(player));
    }
    return CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0]))
        .thenApply(
            ignored -> {
              List<net.whimxiqal.odyssey.Transition<MinecraftStepPayload, MinecraftWorld>> all =
                  new ArrayList<>();
              for (CompletableFuture<List<Transition>> future : futures) {
                for (Transition transition : future.join()) {
                  SpongeTransitionAdapter wrapped = new SpongeTransitionAdapter(transition, this);
                  // A world is reachable only through a transition, so excluding a world/dimension
                  // means dropping any transition that crosses into (or out of) it.
                  if (worldAllowed(wrapped.origin().domain(), excludedWorlds, excludedDimensions)
                      && worldAllowed(
                          wrapped.destination().domain(), excludedWorlds, excludedDimensions)) {
                    all.add(wrapped);
                  }
                }
              }
              return all;
            });
  }

  /**
   * Composes every modifier's break checker into one; a block is breakable only if all permit it.
   */
  private net.whimxiqal.odyssey.minecraft.BreakChecker<OdysseyPlayer> buildBreakChecker(
      List<SearchModificationService> modifiers, ServerPlayer player) {
    List<BreakChecker> checkers = new ArrayList<>();
    for (SearchModificationService modifier : modifiers) {
      BreakChecker checker = modifier.computeBreakChecker(player);
      if (checker != BreakChecker.ALLOW) {
        checkers.add(checker);
      }
    }
    if (checkers.isEmpty()) {
      return null; // no constraint: the mining mode attaches no restriction future at all
    }
    UUID playerId = player.uniqueId();
    return (agent, cell, world, block) -> {
      Optional<ServerPlayer> online = Sponge.server().player(playerId);
      Optional<ServerWorld> serverWorld =
          Sponge.server().worldManager().world(ResourceKey.resolve(world.key()));
      if (online.isEmpty() || serverWorld.isEmpty()) {
        return CompletableFuture.completedFuture(true); // cannot evaluate; do not block mining
      }
      ServerLocation location = ServerLocation.of(serverWorld.get(), cell.x(), cell.y(), cell.z());
      BlockState state = block instanceof SpongeBlock spongeBlock ? spongeBlock.state() : null;
      List<CompletableFuture<Boolean>> results = new ArrayList<>(checkers.size());
      for (BreakChecker checker : checkers) {
        results.add(checker.breakable(online.get(), location, state));
      }
      return allTrue(results);
    };
  }

  /** One composite passability restriction; a cell is impassable if any modifier bars entry. */
  private List<Restriction<OdysseyPlayer, MinecraftWorld>> buildRestrictions(
      List<SearchModificationService> modifiers, ServerPlayer player) {
    List<PassChecker> checkers = new ArrayList<>();
    for (SearchModificationService modifier : modifiers) {
      PassChecker checker = modifier.computePassChecker(player);
      if (checker != PassChecker.ALLOW) {
        checkers.add(checker);
      }
    }
    if (checkers.isEmpty()) {
      return List.of(); // no constraint: Tier-2 skips restriction filtering entirely
    }
    UUID playerId = player.uniqueId();
    Restriction<OdysseyPlayer, MinecraftWorld> restriction =
        (agent, cell, domain) -> {
          Optional<ServerPlayer> online = Sponge.server().player(playerId);
          Optional<ServerWorld> serverWorld =
              Sponge.server().worldManager().world(ResourceKey.resolve(domain.key()));
          if (online.isEmpty() || serverWorld.isEmpty()) {
            return FutureOr.of(false); // cannot evaluate; do not bar entry
          }
          ServerLocation location =
              ServerLocation.of(serverWorld.get(), cell.x(), cell.y(), cell.z());
          List<CompletableFuture<Boolean>> results = new ArrayList<>(checkers.size());
          for (PassChecker checker : checkers) {
            results.add(checker.passable(online.get(), location));
          }
          return FutureOr.from(anyFalse(results));
        };
    return List.of(restriction);
  }

  /** Counts the ender pearls in the player's inventory (read on the server thread). */
  private static int countEnderPearls(ServerPlayer player) {
    int count = 0;
    for (Slot slot : player.inventory().slots()) {
      ItemStack stack = slot.peek();
      if (stack.type().equals(ItemTypes.ENDER_PEARL.get())) {
        count += stack.quantity();
      }
    }
    return count;
  }

  /**
   * A future of whether every input is {@code true} (AND). Already-complete inputs stay immediate.
   */
  private static CompletableFuture<Boolean> allTrue(List<CompletableFuture<Boolean>> futures) {
    return CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0]))
        .thenApply(
            ignored -> {
              for (CompletableFuture<Boolean> future : futures) {
                if (!Boolean.TRUE.equals(future.getNow(Boolean.FALSE))) {
                  return false;
                }
              }
              return true;
            });
  }

  /** A future of whether any input is {@code false} — i.e. some checker bars the action. */
  private static CompletableFuture<Boolean> anyFalse(List<CompletableFuture<Boolean>> futures) {
    return CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0]))
        .thenApply(
            ignored -> {
              for (CompletableFuture<Boolean> future : futures) {
                if (!Boolean.TRUE.equals(future.getNow(Boolean.TRUE))) {
                  return true;
                }
              }
              return false;
            });
  }

  private static boolean worldAllowed(
      MinecraftWorld world, Set<String> excludedWorlds, Set<String> excludedDimensions) {
    return !excludedWorlds.contains(world.key())
        && !excludedDimensions.contains(world.environment().name().toLowerCase(Locale.ROOT));
  }

  @Override
  public MinecraftWorld wrap(ServerWorld world) {
    return worldCache.computeIfAbsent(
        world.key().asString(), key -> new SpongeWorld(world, chunkProvider));
  }
}
