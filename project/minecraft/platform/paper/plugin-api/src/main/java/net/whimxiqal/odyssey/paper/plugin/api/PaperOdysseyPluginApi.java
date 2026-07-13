/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper.plugin.api;

import net.whimxiqal.odyssey.paper.api.PaperOdysseyApi;
import net.whimxiqal.odyssey.plugin.api.PlatformOdysseyPluginApi;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * The Paper-flavored plugin-extension entry point, registered by the Odyssey plugin in Bukkit's
 * {@code ServicesManager}. Fetch it to navigate <em>and</em> to register destinations/navigators —
 * all entirely in native {@link Player}/{@link Location} terms.
 *
 * <p>It extends both the native navigation API ({@link PaperOdysseyApi}) and the generic
 * plugin-extension surface, so a single lookup yields navigation and registration with no
 * intermediate accessor.
 * <p>
 * Use the following pattern to load the API.
 * {@snippet :
 *     RegisteredServiceProvider<PaperOdysseyPluginApi> registration =
 *         Bukkit.getServicesManager().getRegistration(PaperOdysseyPluginApi.class);
 *     if (registration == null) {
 *       // handle error, which happens if the Odyssey plugin is not enabled
 *     }
 *     PaperOdysseyPluginApi odyssey = registration.getProvider();
 *     odyssey.navigatePlayer(player, destination);        // navigate directly
 *     odyssey.registerDestinationProvider(myProvider);    // and/or extend Odyssey
 * }
 */
public interface PaperOdysseyPluginApi
    extends PaperOdysseyApi, PlatformOdysseyPluginApi<Player, Location> {
}
