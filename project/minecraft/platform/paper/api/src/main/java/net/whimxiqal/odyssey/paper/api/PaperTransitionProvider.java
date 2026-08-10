/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper.api;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;

/**
 * Teaches Odyssey travel routes that pathfinding cannot discover on its own — command warps, plugin
 * teleports, minecart networks, and the like. Each route is a {@link PaperTransition}: reach an
 * origin region, arrive at a destination, at some cost.
 *
 * <p><b>Registration.</b> Register your provider as a Bukkit service and Odyssey discovers it
 * automatically — no direct dependency on Odyssey's internals:
 * <pre>{@code
 * getServer().getServicesManager().register(
 *     PaperTransitionProvider.class, myProvider, myPlugin, ServicePriority.Normal);
 * }</pre>
 * Unregister on disable (or call {@code unregisterAll(this)}). Multiple providers may be registered;
 * Odyssey folds them all into every search.
 *
 * <p><b>When and where {@link #compute} runs.</b> Odyssey calls it once at the start of each search,
 * on the thread that initiates the search — normally the main server thread — so reading Bukkit state
 * inside {@code compute} is safe. Return the transitions in a completed future for the common
 * synchronous case; if you must do I/O, do it inside the returned future rather than blocking. Keep
 * any state you read from {@code compute} safe against concurrent mutation from your own commands.
 */
public interface PaperTransitionProvider {

  /**
   * Computes the transitions available to the given player for one search.
   *
   * @param player the player about to be routed (use their world/location to scope your transitions)
   * @return the transitions, in a future (use {@link CompletableFuture#completedFuture} when synchronous)
   */
  CompletableFuture<List<? extends PaperTransition>> compute(Player player);
}
