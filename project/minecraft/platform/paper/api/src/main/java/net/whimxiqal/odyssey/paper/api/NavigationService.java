/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper.api;

import net.whimxiqal.odyssey.minecraft.api.PlatformNavigationService;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * The Paper-flavored developer entry point, registered in Bukkit's {@code ServicesManager} by the
 * Odyssey plugin. It lets other Paper plugins request navigation in native terms ({@link Player},
 * {@link Location}) without touching Odyssey's generic core types.
 *
 * <p>Use the following pattern to load the API.
 *
 * {@snippet :
 *     RegisteredServiceProvider<NavigationService> registration =
 *         Bukkit.getServicesManager().getRegistration(NavigationService.class);
 *     if (registration == null) {
 *       // handle error, which happens if the Odyssey plugin is not enabled
 *     }
 *     NavigationService odysseyApi = registration.getProvider();
 * }
 */
public interface NavigationService extends PlatformNavigationService<Player, Location> {}
