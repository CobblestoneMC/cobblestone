/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepPayload;
import net.whimxiqal.odyssey.paper.api.BoxWorldRegion;
import net.whimxiqal.odyssey.paper.api.PaperTransition;
import net.whimxiqal.odyssey.paper.api.PaperTransitionProvider;
import net.whimxiqal.odyssey.plugin.data.PortalTransition;
import net.whimxiqal.odyssey.plugin.data.PortalTransitionDao;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * The internal {@link PaperTransitionProvider} that surfaces Odyssey's discovered portal links to
 * searches. Registered as a Bukkit service like any third-party transition provider; it reads the
 * persisted transitions on demand, skipping any whose worlds are currently unloaded.
 */
public final class PortalTransitionProvider implements PaperTransitionProvider {

  private static final MinecraftStepPayload PORTAL_PAYLOAD = MinecraftStepPayload.portal();

  private final PortalTransitionDao portals;

  /**
   * Creates the provider.
   *
   * @param portals the portal-transition DAO to read from
   */
  public PortalTransitionProvider(PortalTransitionDao portals) {
    this.portals = portals;
  }

  @Override
  public CompletableFuture<List<? extends PaperTransition>> compute(Player player) {
    List<PaperTransition> result = new ArrayList<>();
    for (PortalTransition portal : portals.all()) {
      World fromWorld = worldOf(portal.fromWorld());
      World toWorld = worldOf(portal.toWorld());
      if (fromWorld == null || toWorld == null) {
        continue; // a world unloaded since discovery; skip until it is back
      }
      BoxWorldRegion origin = BoxWorldRegion.of(
          new Location(fromWorld, portal.minX(), portal.minY(), portal.minZ()),
          new Location(fromWorld, portal.maxX(), portal.maxY(), portal.maxZ()));
      Location destination = new Location(toWorld, portal.toX(), portal.toY(), portal.toZ());
      result.add(PaperTransition.of(origin, destination, portal.cost(), PORTAL_PAYLOAD));
    }
    return CompletableFuture.completedFuture(result);
  }

  private static World worldOf(String key) {
    NamespacedKey namespacedKey = NamespacedKey.fromString(key);
    return namespacedKey == null ? null : Bukkit.getWorld(namespacedKey);
  }
}
