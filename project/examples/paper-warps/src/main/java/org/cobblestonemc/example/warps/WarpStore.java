/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.example.warps;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * The destinations, warps, and portals, held in memory and mirrored to {@code warps.yml}. Small
 * scale, so every mutation rewrites the file — simplicity over cleverness for an example. Reads are
 * thread-safe: the transition provider iterates from Cobblestone's search thread while admin
 * commands mutate on the main thread.
 */
final class WarpStore {

  private final File file;
  private final Logger logger;
  private final ConcurrentHashMap<String, Destination> destinations = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Warp> warps = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Portal> portals = new ConcurrentHashMap<>();

  WarpStore(File file, Logger logger) {
    this.file = file;
    this.logger = logger;
  }

  /** Normalises a name to its storage key (case-insensitive). */
  static String key(String name) {
    return name.toLowerCase(Locale.ROOT);
  }

  /** Loads everything from disk, replacing the in-memory sets. */
  void load() {
    destinations.clear();
    warps.clear();
    portals.clear();
    if (!file.exists()) {
      return;
    }
    YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
    loadDestinations(yaml.getConfigurationSection("destinations"));
    loadWarps(yaml.getConfigurationSection("warps"));
    loadPortals(yaml.getConfigurationSection("portals"));
    logger.info(
        "Loaded "
            + destinations.size()
            + " destination(s), "
            + warps.size()
            + " warp(s), "
            + portals.size()
            + " portal(s).");
  }

  private void loadDestinations(ConfigurationSection root) {
    if (root == null) {
      return;
    }
    for (String name : root.getKeys(false)) {
      ConfigurationSection d = root.getConfigurationSection(name);
      if (d == null) {
        continue;
      }
      destinations.put(
          key(name),
          new Destination(
              key(name),
              d.getString("world", ""),
              d.getDouble("x"),
              d.getDouble("y"),
              d.getDouble("z"),
              (float) d.getDouble("yaw"),
              (float) d.getDouble("pitch")));
    }
  }

  private void loadWarps(ConfigurationSection root) {
    if (root == null) {
      return;
    }
    for (String name : root.getKeys(false)) {
      ConfigurationSection w = root.getConfigurationSection(name);
      if (w == null) {
        continue;
      }
      warps.put(
          key(name),
          new Warp(
              key(name),
              w.getString("world", ""),
              w.getDouble("x"),
              w.getDouble("y"),
              w.getDouble("z"),
              (float) w.getDouble("yaw"),
              (float) w.getDouble("pitch"),
              w.getDouble("cost", 5.0)));
    }
  }

  private void loadPortals(ConfigurationSection root) {
    if (root == null) {
      return;
    }
    for (String name : root.getKeys(false)) {
      ConfigurationSection p = root.getConfigurationSection(name);
      if (p == null) {
        continue;
      }
      portals.put(
          key(name),
          new Portal(
              key(name),
              p.getString("world", ""),
              p.getInt("minX"),
              p.getInt("minY"),
              p.getInt("minZ"),
              p.getInt("maxX"),
              p.getInt("maxY"),
              p.getInt("maxZ"),
              key(p.getString("destination", "")),
              p.getDouble("cost", 5.0)));
    }
  }

  private void save() {
    YamlConfiguration yaml = new YamlConfiguration();
    ConfigurationSection destinationsRoot = yaml.createSection("destinations");
    for (Destination d : destinations.values()) {
      ConfigurationSection s = destinationsRoot.createSection(d.name());
      s.set("world", d.world());
      s.set("x", d.x());
      s.set("y", d.y());
      s.set("z", d.z());
      s.set("yaw", (double) d.yaw());
      s.set("pitch", (double) d.pitch());
    }
    ConfigurationSection warpsRoot = yaml.createSection("warps");
    for (Warp w : warps.values()) {
      ConfigurationSection s = warpsRoot.createSection(w.name());
      s.set("world", w.world());
      s.set("x", w.x());
      s.set("y", w.y());
      s.set("z", w.z());
      s.set("yaw", (double) w.yaw());
      s.set("pitch", (double) w.pitch());
      s.set("cost", w.cost());
    }
    ConfigurationSection portalsRoot = yaml.createSection("portals");
    for (Portal p : portals.values()) {
      ConfigurationSection s = portalsRoot.createSection(p.name());
      s.set("world", p.world());
      s.set("minX", p.minX());
      s.set("minY", p.minY());
      s.set("minZ", p.minZ());
      s.set("maxX", p.maxX());
      s.set("maxY", p.maxY());
      s.set("maxZ", p.maxZ());
      s.set("destination", p.destination());
      s.set("cost", p.cost());
    }
    try {
      yaml.save(file);
    } catch (IOException e) {
      logger.log(Level.SEVERE, "Failed to save warps.yml", e);
    }
  }

  Collection<Destination> destinations() {
    return destinations.values();
  }

  Optional<Destination> getDestination(String name) {
    return Optional.ofNullable(destinations.get(key(name)));
  }

  void putDestination(Destination destination) {
    destinations.put(destination.name(), destination);
    save();
  }

  boolean removeDestination(String name) {
    if (destinations.remove(key(name)) == null) {
      return false;
    }
    save();
    return true;
  }

  Collection<Warp> warps() {
    return warps.values();
  }

  Optional<Warp> getWarp(String name) {
    return Optional.ofNullable(warps.get(key(name)));
  }

  void putWarp(Warp warp) {
    warps.put(warp.name(), warp);
    save();
  }

  boolean removeWarp(String name) {
    if (warps.remove(key(name)) == null) {
      return false;
    }
    save();
    return true;
  }

  Collection<Portal> portals() {
    return portals.values();
  }

  Optional<Portal> getPortal(String name) {
    return Optional.ofNullable(portals.get(key(name)));
  }

  void putPortal(Portal portal) {
    portals.put(portal.name(), portal);
    save();
  }

  boolean removePortal(String name) {
    if (portals.remove(key(name)) == null) {
      return false;
    }
    save();
    return true;
  }
}
