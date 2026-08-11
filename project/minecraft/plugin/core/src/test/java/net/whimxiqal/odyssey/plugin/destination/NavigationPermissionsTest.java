/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.plugin.destination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/** Default-allow semantics and node convention for the {@code odyssey.navigate.*} gate. */
class NavigationPermissionsTest {

  private static final List<String> ADDRESS = List.of("essentials", "home", "base");
  private static final String NODE = "odyssey.navigate.essentials.home.base";
  private static final Predicate<String> NONE = node -> false;

  @Test
  void buildsTheNodeFromTheAddress() {
    assertEquals(NODE, NavigationPermissions.node(ADDRESS));
    assertEquals("odyssey.navigate", NavigationPermissions.node(List.of()));
  }

  @Test
  void allowedByDefaultWhenUnset() {
    assertTrue(NavigationPermissions.allowed(ADDRESS, NONE, NONE));
  }

  @Test
  void deniedWhenSetFalse() {
    assertFalse(NavigationPermissions.allowed(ADDRESS, NODE::equals, NONE));
  }

  @Test
  void allowedWhenSetTrue() {
    assertTrue(NavigationPermissions.allowed(ADDRESS, NODE::equals, NODE::equals));
  }
}
