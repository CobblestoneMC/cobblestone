/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.destination;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import org.cobblestonemc.api.Destination;
import org.cobblestonemc.minecraft.api.WorldRegion;
import org.cobblestonemc.plugin.api.MinecraftDestination;
import org.cobblestonemc.plugin.api.Validity;

/**
 * A destination worked out only once it is actually used — for one whose state is expensive to read
 * or must be read on the player's own thread, such as {@code target} (a ray trace) or {@code
 * surface} (whether the player is already in the open). Tab-completion materializes every leaf it
 * walks past, possibly off the main thread, but only asks for its name, permissions and options,
 * which this answers without building the destination.
 *
 * <p>The first call to anything else builds it, once, and delegates from then on.
 *
 * @param <W> the platform world type
 * @param <V> the platform vector type
 */
public final class LazyMinecraftDestination<W, V> implements MinecraftDestination<W, V> {

  private final Component displayName;
  private final List<String> permissions;
  private final Supplier<? extends MinecraftDestination<W, V>> factory;
  private volatile MinecraftDestination<W, V> built;

  /**
   * Creates a lazy destination.
   *
   * @param displayName the name, which the built destination should share
   * @param permissions the required permissions, which the built destination should share
   * @param factory builds the destination; called at most once
   */
  public LazyMinecraftDestination(
      Component displayName,
      List<String> permissions,
      Supplier<? extends MinecraftDestination<W, V>> factory) {
    this.displayName = Objects.requireNonNull(displayName, "displayName");
    this.permissions = List.copyOf(permissions);
    this.factory = Objects.requireNonNull(factory, "factory");
  }

  private MinecraftDestination<W, V> built() {
    MinecraftDestination<W, V> result = built;
    if (result == null) {
      synchronized (this) {
        result = built;
        if (result == null) {
          result = factory.get();
          built = result;
        }
      }
    }
    return result;
  }

  @Override
  public Component displayName() {
    return displayName;
  }

  @Override
  public List<String> permissions() {
    return permissions;
  }

  @Override
  public Destination<WorldRegion<W, V>> destination() {
    return built().destination();
  }

  @Override
  public boolean isMobile() {
    return built().isMobile();
  }

  @Override
  public Optional<Component> detail() {
    return built().detail();
  }

  @Override
  public Validity validity() {
    return built().validity();
  }

  @Override
  public boolean exists() {
    return built().exists();
  }
}
