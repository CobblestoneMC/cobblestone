/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.platform;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.cobblestonemc.Position;
import org.cobblestonemc.minecraft.CobblestonePlayer;
import org.cobblestonemc.minecraft.MinecraftWorld;
import org.jetbrains.annotations.Nullable;

/**
 * The agent a scenario navigates: a player whose capabilities are declared rather than read off a
 * live server.
 *
 * <p>Capabilities are what decide how a search may move, so they are the main axis a scenario
 * varies. Two scenarios over one capture — one with a boat, one without — are the cheapest useful
 * test in the whole corpus, because the terrain is already paid for.
 *
 * <p>Immutable, so one instance can serve the repeated runs of a benchmark iteration without any
 * chance of a solve leaving state behind for the next.
 */
public final class StonebrickPlayer implements CobblestonePlayer {

  private final UUID uuid;
  private final Set<String> permissions;
  private final boolean canFly;
  private final boolean canGlide;
  private final boolean hasBoat;
  private final boolean inBoat;
  private final @Nullable Position<MinecraftWorld> lastHorse;

  private StonebrickPlayer(Builder builder) {
    this.uuid = builder.uuid;
    this.permissions = Set.copyOf(builder.permissions);
    this.canFly = builder.canFly;
    this.canGlide = builder.canGlide;
    this.hasBoat = builder.hasBoat;
    this.inBoat = builder.inBoat;
    this.lastHorse = builder.lastHorse;
  }

  /**
   * Creates a builder for a player with no capabilities and no permissions.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  @Override
  public UUID uuid() {
    return uuid;
  }

  @Override
  public boolean hasPermission(String node) {
    return permissions.contains(node);
  }

  @Override
  public boolean canFly() {
    return canFly;
  }

  @Override
  public boolean canGlide() {
    return canGlide;
  }

  @Override
  public boolean hasBoatInInventory() {
    return hasBoat;
  }

  @Override
  public boolean isInBoat() {
    return inBoat;
  }

  @Override
  public Optional<Position<MinecraftWorld>> lastRiddenHorse() {
    return Optional.ofNullable(lastHorse);
  }

  @Override
  public Locale locale() {
    return Locale.ENGLISH;
  }

  @Override
  public String toString() {
    return "StonebrickPlayer[fly=" + canFly + ", boat=" + hasBoat + "]";
  }

  /** Builds a scenario's agent. */
  public static final class Builder {

    private UUID uuid = new UUID(0L, 1L);
    private Set<String> permissions = Set.of();
    private boolean canFly;
    private boolean canGlide;
    private boolean hasBoat;
    private boolean inBoat;
    private @Nullable Position<MinecraftWorld> lastHorse;

    private Builder() {}

    /**
     * Sets the player's id. Fixed by default, since a random one would be one more thing varying
     * between two runs that are meant to be comparable.
     *
     * @param value the uuid
     * @return this builder
     */
    public Builder uuid(UUID value) {
      this.uuid = value;
      return this;
    }

    /**
     * Sets the permission nodes the player holds.
     *
     * @param nodes the nodes
     * @return this builder
     */
    public Builder permissions(Set<String> nodes) {
      this.permissions = nodes;
      return this;
    }

    /**
     * Sets whether the player can fly.
     *
     * @param value the value
     * @return this builder
     */
    public Builder canFly(boolean value) {
      this.canFly = value;
      return this;
    }

    /**
     * Sets whether the player can elytra-glide.
     *
     * @param value the value
     * @return this builder
     */
    public Builder canGlide(boolean value) {
      this.canGlide = value;
      return this;
    }

    /**
     * Sets whether the player carries a boat.
     *
     * @param value the value
     * @return this builder
     */
    public Builder hasBoat(boolean value) {
      this.hasBoat = value;
      return this;
    }

    /**
     * Sets whether the player is already in a boat.
     *
     * @param value the value
     * @return this builder
     */
    public Builder inBoat(boolean value) {
      this.inBoat = value;
      return this;
    }

    /**
     * Sets the position of the player's last ridden horse.
     *
     * @param value the position, or {@code null}
     * @return this builder
     */
    public Builder lastRiddenHorse(@Nullable Position<MinecraftWorld> value) {
      this.lastHorse = value;
      return this;
    }

    /**
     * Builds the player.
     *
     * @return the player
     */
    public StonebrickPlayer build() {
      return new StonebrickPlayer(this);
    }
  }
}
