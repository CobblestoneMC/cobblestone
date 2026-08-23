/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.registry;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * A thread-safe registry of values Cobblestone collects from other plugins and its own extensions,
 * keyed by the {@code owner} that supplied each one — so an owner registers at most one value, and
 * everything a departing owner registered can be {@link #purge purged} at once (the one bookkeeping
 * the Bukkit service manager used to do for us, now that Cobblestone owns the collection).
 *
 * <p>Iteration is in ascending key order, not registration order: an owner's position in the
 * destination forest must not depend on plugin load order, which the server may change between
 * restarts.
 *
 * <p>Writes (register/purge) happen on the server thread during plugin enable/disable or reload;
 * reads ({@link #map}) happen on search worker threads, so the backing map is concurrent.
 *
 * @param <T> the registered value type (a destination provider, a search modifier, …)
 */
public final class OwnedRegistry<T> {

  private final ConcurrentSkipListMap<String, T> entries = new ConcurrentSkipListMap<>();
  private final Map<String, T> view = Collections.unmodifiableMap(entries);

  /**
   * Registers a value under an owner.
   *
   * @param owner the registering owner's stable id (a plugin name, or an extension id)
   * @param value the value to register
   * @throws IllegalArgumentException if this owner already registered a value
   */
  public void register(String owner, T value) {
    if (entries.putIfAbsent(owner, value) != null) {
      throw new DuplicateRegistrationException(owner);
    }
  }

  /**
   * The registered values by owner, in ascending owner order. The returned map is an unmodifiable
   * live view: safe to read from any thread, and it reflects later registrations.
   *
   * @return the values by owner
   */
  public Map<String, T> map() {
    return view;
  }

  /**
   * Removes and forgets what an owner registered.
   *
   * @param owner the owner whose registration to drop
   * @return the removed value, or {@code null} if the owner had registered nothing
   */
  public T purge(String owner) {
    return entries.remove(owner);
  }
}
