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
import java.util.function.BooleanSupplier;
import net.kyori.adventure.text.Component;
import org.cobblestonemc.DomainRegion;
import org.cobblestonemc.api.Destination;
import org.cobblestonemc.minecraft.MinecraftWorld;
import org.cobblestonemc.minecraft.RegionFactory;
import org.cobblestonemc.plugin.api.MinecraftDestination;

/**
 * What the {@code /navigate} command navigates to: Cobblestone's own form of a destination, with
 * its regions already in the search's terms ({@link DomainRegion}s, each made per search by a
 * {@link RegionFactory}).
 *
 * <p>An integration's {@link MinecraftDestination} speaks its platform's region type, and becomes
 * one of these when its tree joins Cobblestone's (see {@link PlatformTrees}). Cobblestone's own
 * destinations are written as these directly, which lets them use regions that an integration's
 * region type cannot express — such as "anywhere open to the sky", whose extent depends on the
 * blocks the search reads.
 *
 * @param destination the regions, any of which will do; re-read on every search, so a moving target
 *     is followed by a live trip, and each made for that search
 * @param displayName the human-facing name
 * @param mobile whether it can move (see {@link MinecraftDestination#isMobile()})
 * @param detail the hover detail (see {@link MinecraftDestination#detail()})
 * @param existence whether it still exists (see {@link MinecraftDestination#exists()})
 */
public record NavigationTarget(
    Destination<RegionFactory> destination,
    Component displayName,
    boolean mobile,
    Optional<Component> detail,
    BooleanSupplier existence) {

  /** Canonical constructor; everything is required. */
  public NavigationTarget {
    Objects.requireNonNull(destination, "destination");
    Objects.requireNonNull(displayName, "displayName");
    Objects.requireNonNull(detail, "detail");
    Objects.requireNonNull(existence, "existence");
  }

  /**
   * Begins a stationary target without hover detail, which always exists.
   *
   * @param name the human-facing name
   * @param destination the regions, re-read on every search
   * @return a builder
   */
  public static Builder builder(
      String name, Destination<DomainRegion<MinecraftWorld>> destination) {
    return new Builder(
        Component.text(name), () -> destination.regions().stream().map(RegionFactory::of).toList());
  }

  /**
   * Begins a stationary target without hover detail, which always exists, whose one region is made
   * per search; for a region that reads the search's blocks.
   *
   * @param name the human-facing name
   * @param region makes the region for each search
   * @return a builder
   */
  public static Builder builder(String name, RegionFactory region) {
    return new Builder(Component.text(name), () -> List.of(region));
  }

  /**
   * Returns whether the target still exists; see {@link MinecraftDestination#exists()}.
   *
   * @return {@code false} once it is gone for good
   */
  public boolean exists() {
    return existence.getAsBoolean();
  }

  /** Builds a {@link NavigationTarget}. */
  public static final class Builder {

    private final Component displayName;
    private final Destination<RegionFactory> destination;
    private boolean mobile;
    private Component detail;
    private BooleanSupplier existence = () -> true;

    private Builder(Component displayName, Destination<RegionFactory> destination) {
      this.displayName = displayName;
      this.destination = destination;
    }

    /**
     * Marks the target as one that moves, so trips to it are live by default.
     *
     * @return this builder
     */
    public Builder mobile() {
      this.mobile = true;
      return this;
    }

    /**
     * Sets the hover detail.
     *
     * @param detail the detail
     * @return this builder
     */
    public Builder detail(Component detail) {
      this.detail = detail;
      return this;
    }

    /**
     * Sets how to tell that the target is gone for good, ending a trip to it.
     *
     * @param existence polled during a trip
     * @return this builder
     */
    public Builder existence(BooleanSupplier existence) {
      this.existence = existence;
      return this;
    }

    /**
     * Builds the target.
     *
     * @return the target
     */
    public NavigationTarget build() {
      return new NavigationTarget(
          destination, displayName, mobile, Optional.ofNullable(detail), existence);
    }
  }
}
