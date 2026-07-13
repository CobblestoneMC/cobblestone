/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.plugin.api;

import net.whimxiqal.odyssey.minecraft.api.PlatformOdysseyApi;

/**
 * The opinionated plugin-extension surface: <b>is</b> the platform navigation API
 * ({@link PlatformOdysseyApi}) and adds the ability to register destinations and navigators with the
 * running Odyssey plugin. Because it extends the platform API, a caller navigates directly — no
 * intermediate accessor to reach the navigation methods.
 *
 * <p>Odyssey's plugin registers <b>one</b> instance of the platform-bound sub-interface (e.g.
 * {@code PaperOdysseyPluginApi}) in the server's service manager; other plugins fetch it and use it
 * both to navigate and to register their own destinations/navigators. Transition registration is
 * inherited from the platform API (transitions are an algorithm-graph concern); destinations and
 * navigators (plugin opinions) are added here.
 *
 * <p>Every method is expressed in the platform's native player/location types, so downstream
 * developers never touch Odyssey's internal abstractions.
 *
 * @param <P> the native player type (e.g. {@code org.bukkit.entity.Player})
 * @param <L> the native location type (e.g. {@code org.bukkit.Location})
 */
public interface PlatformOdysseyPluginApi<P, L> extends PlatformOdysseyApi<P, L> {

  /**
   * Registers a destination provider so its targets become navigable and tab-completable.
   *
   * @param provider the provider to register
   */
  void registerDestinationProvider(DestinationProvider<P> provider);

  /**
   * Registers a navigator factory under an id (lower-cased). Players select it with
   * {@code -navigator <id>}.
   *
   * @param id the navigator id
   * @param factory the factory to register
   */
  void registerNavigatorFactory(String id, NavigatorFactory<P, L> factory);
}
