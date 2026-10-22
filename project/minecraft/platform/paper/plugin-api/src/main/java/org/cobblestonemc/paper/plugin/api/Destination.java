/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper.plugin.api;

import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.World;
import org.cobblestonemc.minecraft.api.WorldRegion;
import org.cobblestonemc.paper.api.SingleCellWorldRegion;
import org.cobblestonemc.paper.api.Transition;
import org.cobblestonemc.plugin.api.DestinationEntry;
import org.cobblestonemc.plugin.api.DestinationTree;
import org.cobblestonemc.plugin.api.MinecraftDestination;
import org.joml.Vector3i;

/**
 * Factories for {@link MinecraftDestination}s with Paper's world/vector types already bound — the
 * destination counterpart to {@link Transition}, so integrations never echo the {@code
 * WorldRegion<World, Vector3i>} generics. The region supplier is re-evaluated each search, so a
 * moving target (a home, a town's claims) stays current.
 *
 * <p>{@link #at}, {@link #region} and {@link #regions} cover most destinations. For one that moves,
 * can disappear, or has its own hover detail, use {@link #builder}.
 *
 * <p>Navigability is gated by Cobblestone's {@code cobblestone.navigate.*} permission. A leaf's own
 * {@link org.cobblestonemc.plugin.api.DestinationLeaf#permissions() permissions} are reserved for
 * genuine hard requirements.
 */
public final class Destination {

  private Destination() {}

  /**
   * Begins a tree of destinations, for a {@link DestinationService} to hand Cobblestone.
   *
   * @return a new, empty tree builder
   */
  public static DestinationTree.Builder<MinecraftDestination<World, Vector3i>> tree() {
    return DestinationTree.builder();
  }

  /**
   * Begins one entry of a tree, for when it needs more than {@link
   * DestinationTree.Builder#destination} or {@link DestinationTree.Builder#subtree} give it, such
   * as both roles at once. Add it with {@link DestinationTree.Builder#entry(String,
   * DestinationEntry.Builder)}.
   *
   * @return a new, empty entry builder
   */
  public static DestinationEntry.Builder<MinecraftDestination<World, Vector3i>> entry() {
    return DestinationEntry.builder();
  }

  /**
   * A destination at a single location (its block cell), or an empty one if {@code location} is
   * null. Its coordinates are shown when the player hovers over it.
   *
   * @param location the location, or null
   * @param name the display name
   * @return the destination
   */
  public static MinecraftDestination<World, Vector3i> at(Location location, String name) {
    if (location == null || location.getWorld() == null) {
      return regions(List::of, name);
    }
    return builder(name, () -> List.of(SingleCellWorldRegion.of(location)))
        .detail(
            Component.text(
                location.getBlockX()
                    + ", "
                    + location.getBlockY()
                    + ", "
                    + location.getBlockZ()
                    + " ("
                    + location.getWorld().getKey().asString()
                    + ")"))
        .build();
  }

  /**
   * A destination covering one region.
   *
   * @param region the region
   * @param name the display name
   * @return the destination
   */
  public static MinecraftDestination<World, Vector3i> region(
      WorldRegion<World, Vector3i> region, String name) {
    return regions(() -> List.of(region), name);
  }

  /**
   * A destination covering a set of regions, re-resolved each search (the nearest one is the goal).
   *
   * @param regions supplies the current regions
   * @param name the display name
   * @return the destination
   */
  public static MinecraftDestination<World, Vector3i> regions(
      Supplier<List<WorldRegion<World, Vector3i>>> regions, String name) {
    return builder(name, regions).build();
  }

  /**
   * Begins a destination covering a set of regions, re-resolved each search. Left as it is, it
   * matches {@link #regions}: stationary, with no hover detail, and never gone.
   *
   * @param name the display name
   * @param regions supplies the current regions
   * @return a builder
   */
  public static Builder builder(String name, Supplier<List<WorldRegion<World, Vector3i>>> regions) {
    return new Builder(Component.text(name), regions);
  }

  /** Builds a {@link MinecraftDestination}; see {@link Destination#builder}. */
  public static final class Builder {

    private final Component displayName;
    private final Supplier<List<WorldRegion<World, Vector3i>>> regions;
    private boolean mobile;
    private Component detail;
    private BooleanSupplier existence = () -> true;

    private Builder(Component displayName, Supplier<List<WorldRegion<World, Vector3i>>> regions) {
      this.displayName = displayName;
      this.regions = regions;
    }

    /**
     * Marks the destination as one that moves (an NPC, a player), so trips to it are live by
     * default. See {@link MinecraftDestination#isMobile()}.
     *
     * @return this builder
     */
    public Builder mobile() {
      this.mobile = true;
      return this;
    }

    /**
     * Sets the hover detail. See {@link MinecraftDestination#detail()}.
     *
     * @param detail the detail
     * @return this builder
     */
    public Builder detail(Component detail) {
      this.detail = detail;
      return this;
    }

    /**
     * Sets how to tell that the destination is gone for good, which ends a trip to it. See {@link
     * MinecraftDestination#exists()}.
     *
     * @param existence returns {@code false} once the destination is gone
     * @return this builder
     */
    public Builder existence(BooleanSupplier existence) {
      this.existence = existence;
      return this;
    }

    /**
     * Builds the destination.
     *
     * @return the destination
     */
    public MinecraftDestination<World, Vector3i> build() {
      return new Impl(regions::get, displayName, mobile, Optional.ofNullable(detail), existence);
    }
  }

  private record Impl(
      org.cobblestonemc.api.Destination<WorldRegion<World, Vector3i>> destination,
      Component displayName,
      boolean mobile,
      Optional<Component> detail,
      BooleanSupplier existence)
      implements MinecraftDestination<World, Vector3i> {

    @Override
    public boolean isMobile() {
      return mobile;
    }

    @Override
    public boolean exists() {
      return existence.getAsBoolean();
    }
  }
}
