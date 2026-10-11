/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.destination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import org.cobblestonemc.plugin.api.MinecraftDestination;
import org.cobblestonemc.plugin.api.Validity;
import org.junit.jupiter.api.Test;

class LazyMinecraftDestinationTest {

  @Test
  void tabCompletionNeverBuildsIt() {
    AtomicInteger builds = new AtomicInteger();
    MinecraftDestination<Object, Object> lazy =
        new LazyMinecraftDestination<>(
            Component.text("target"),
            List.of("some.node"),
            () -> {
              builds.incrementAndGet();
              return new SimpleMinecraftDestination<>(
                  List::of, Component.text("target"), List.of());
            });

    // What the resolver reads while suggesting.
    assertEquals(Component.text("target"), lazy.displayName());
    assertEquals(List.of("some.node"), lazy.permissions());
    assertTrue(lazy.options().isEmpty());
    assertEquals(0, builds.get());
  }

  @Test
  void buildsOnceAndDelegates() {
    AtomicInteger builds = new AtomicInteger();
    Component reason = Component.text("You're already on the surface!");
    MinecraftDestination<Object, Object> lazy =
        new LazyMinecraftDestination<>(
            Component.text("surface"),
            List.of(),
            () -> {
              builds.incrementAndGet();
              return SimpleMinecraftDestination.<Object, Object>invalid(
                      Component.text("surface"), List.of(), reason)
                  .withExistence(() -> false);
            });

    Validity validity = lazy.validity();
    assertEquals(reason, assertInstanceOf(Validity.Invalid.class, validity).reason());
    assertFalse(validity.isValid());
    assertFalse(lazy.exists());
    assertTrue(lazy.destination().regions().isEmpty());
    assertEquals(1, builds.get());
  }

  @Test
  void aPlainDestinationIsValidAndAlwaysExists() {
    MinecraftDestination<Object, Object> plain =
        new SimpleMinecraftDestination<>(List::of, Component.text("spawn"), List.of());
    assertTrue(plain.validity().isValid());
    assertTrue(plain.exists());
  }
}
