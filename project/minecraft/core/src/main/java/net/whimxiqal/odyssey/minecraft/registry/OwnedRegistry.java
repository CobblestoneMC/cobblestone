/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.minecraft.registry;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A thread-safe, owner-keyed registry of values Odyssey collects from other plugins and its own
 * extensions. Registration order is preserved; each value remembers the {@code owner} that supplied
 * it so everything a departing owner registered can be {@link #purge purged} at once (the one
 * bookkeeping the Bukkit service manager used to do for us, now that Odyssey owns the collection).
 *
 * <p>Writes (register/purge) happen on the server thread during plugin enable/disable or reload;
 * reads ({@link #map}) happen on search worker threads, so the backing list is copy-on-write.
 *
 * @param <T> the registered value type (a destination provider, a search modifier, …)
 */
public final class OwnedRegistry<T> {

  private final ConcurrentHashMap<String, T> entries = new ConcurrentHashMap<>();

  /**
   * Registers a value under an owner.
   *
   * @param owner the registering owner's stable id (a plugin name, or an extension id)
   * @param value the value to register
   */
  public void register(String owner, T value) {
    var old = entries.putIfAbsent(owner, value);
    if (old != null) {
      throw new IllegalArgumentException("A value was already registered for owner " + owner);
    }
  }

  /**
   * A snapshot of the registered values, in registration order.
   *
   * @return the values
   */
  public Map<String, T> map() {
    return Map.copyOf(entries);
  }

  /**
   * Removes and forgets everything an owner registered.
   *
   * @param owner the owner whose registrations to drop
   * @return how many registrations were removed
   */
  public T purge(String owner) {
    return entries.remove(owner);
  }
}
