/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.plugin;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.whimxiqal.odyssey.api.SearchHandle;
import net.whimxiqal.odyssey.api.SearchSettings;
import net.whimxiqal.odyssey.api.Step;
import net.whimxiqal.odyssey.minecraft.api.*;
import net.whimxiqal.odyssey.plugin.api.DestinationProvider;
import net.whimxiqal.odyssey.plugin.api.NavigatorFactory;
import net.whimxiqal.odyssey.plugin.api.PlatformOdysseyPluginApi;

/**
 * The platform-neutral implementation of the plugin-extension surface. Since
 * {@link PlatformOdysseyPluginApi} <b>is</b> a {@link PlatformOdysseyApi}, this impl <b>composes</b> a
 * concrete platform API and forwards every navigation call to it, while owning the registered
 * destination providers and navigator factories itself. Delegation (rather than deriving from a
 * platform impl) keeps the shared registration state in one place and lets each platform reuse its
 * own unchanged {@code PlatformOdysseyApi} implementation.
 *
 * <p>Each platform subclasses this to bind the native types (e.g. {@code Player}/{@code Location}); no
 * covariant overrides are needed because the platform API already speaks native types.
 *
 * @param <P> the native player type
 * @param <L> the native location type
 */
public class OdysseyPluginApiImpl<P, L> implements PlatformOdysseyPluginApi<P, L> {

  private final PlatformOdysseyApi<P, L> platform;
  private final List<DestinationProvider<P>> destinationProviders = new CopyOnWriteArrayList<>();
  private final Map<String, NavigatorFactory<P, L>> navigatorFactories = new ConcurrentHashMap<>();

  /**
   * Creates the impl over a platform API to which navigation is delegated.
   *
   * @param platform the navigation library to forward navigation calls to
   */
  public OdysseyPluginApiImpl(PlatformOdysseyApi<P, L> platform) {
    this.platform = Objects.requireNonNull(platform, "platform");
  }

  @Override
  public SearchHandle<Step<L, MinecraftStepPayload>> navigatePlayer(
      P player, L destination, SearchSettings settings) {
    return platform.navigatePlayer(player, destination, settings);
  }

  @Override
  public SearchHandle<Step<L, MinecraftStepPayload>> navigatePlayerToRegion(
      P player, L location1, L location2, SearchSettings settings) {
    return platform.navigatePlayerToRegion(player, location1, location2, settings);
  }

  @Override
  public void registerDestinationProvider(DestinationProvider<P> provider) {
    destinationProviders.add(Objects.requireNonNull(provider, "provider"));
  }

  @Override
  public void registerNavigatorFactory(String id, NavigatorFactory<P, L> factory) {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(factory, "factory");
    navigatorFactories.put(id.toLowerCase(Locale.ROOT), factory);
  }

  /**
   * Returns an immutable snapshot of the registered destination providers, in registration order.
   *
   * @return the destination providers
   */
  public List<DestinationProvider<P>> destinationProviders() {
    return List.copyOf(destinationProviders);
  }

  /**
   * Returns the navigator factory registered under the given id (case-insensitive), or {@code null}.
   *
   * @param id the navigator id
   * @return the factory, or {@code null}
   */
  public NavigatorFactory<P, L> navigatorFactory(String id) {
    return navigatorFactories.get(id.toLowerCase(Locale.ROOT));
  }

  /**
   * Returns an immutable snapshot of the registered navigator factories, keyed by lower-cased id.
   *
   * @return the navigator factories
   */
  public Map<String, NavigatorFactory<P, L>> navigatorFactories() {
    return Map.copyOf(navigatorFactories);
  }
}
