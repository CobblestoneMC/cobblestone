/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper.api;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.cobblestonemc.minecraft.api.PlatformNavigationService;

/**
 * The Paper-flavored developer entry point, registered in Bukkit's {@code ServicesManager} by the
 * Cobblestone plugin. It lets other Paper plugins request navigation in native terms ({@link
 * Player}, {@link Location}) without touching Cobblestone's generic core types.
 *
 * <p>Use the following pattern to load the API.
 *
 * {@snippet :
 *     RegisteredServiceProvider<NavigationService> registration =
 *         Bukkit.getServicesManager().getRegistration(NavigationService.class);
 *     if (registration == null) {
 *       // handle error, which happens if the Cobblestone plugin is not enabled
 *     }
 *     NavigationService cobblestoneApi = registration.getProvider();
 * }
 */
public interface NavigationService extends PlatformNavigationService<Player, Location> {}
