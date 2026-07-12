/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.minecraft.api;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A mutable, thread-safe collection of {@link PlatformSingleCellTransitionProvider}s in native
 * platform terms.
 *
 * <p>The registry is <b>owned by the plugin layer</b>, not by the platform API implementation: the
 * plugin constructs it, hands it to the {@link PlatformOdysseyApi} implementation, and keeps its own
 * reference. The API only ever {@linkplain #register(PlatformSingleCellTransitionProvider) registers}
 * into it and {@linkplain #providers() reads} from it, so a plugin can seed default transition
 * providers and rebuild the API without losing developer registrations.
 *
 * @param <P> the native player type (e.g. {@code org.bukkit.entity.Player})
 * @param <L> the native location type (e.g. {@code org.bukkit.Location})
 */
public final class TransitionRegistry<P, L> {

  private final List<PlatformSingleCellTransitionProvider<P, L>> providers = new CopyOnWriteArrayList<>();

  /**
   * Adds a transition provider to this registry.
   *
   * @param provider the provider to register
   */
  public void register(PlatformSingleCellTransitionProvider<P, L> provider) {
    providers.add(provider);
  }

  /**
   * Returns an immutable snapshot of the currently registered providers.
   *
   * @return the registered providers
   */
  public List<PlatformSingleCellTransitionProvider<P, L>> providers() {
    return List.copyOf(providers);
  }
}
