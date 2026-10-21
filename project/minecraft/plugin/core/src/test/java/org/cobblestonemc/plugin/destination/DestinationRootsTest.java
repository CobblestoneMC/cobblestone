/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.destination;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.cobblestonemc.plugin.api.DestinationLeaf;
import org.cobblestonemc.plugin.api.DestinationNode;
import org.junit.jupiter.api.Test;

class DestinationRootsTest {

  @Test
  void keepsOnlyProvidersWithSomethingToOfferInKeyOrder() {
    Map<String, DestinationProvider<String>> providers = new LinkedHashMap<>();
    DestinationNode<NavigationTarget> spawn =
        DestinationNode.<NavigationTarget>builder()
            .destination("spawn", DestinationLeaf.<NavigationTarget>of(() -> null))
            .build();
    providers.put("warps", player -> spawn);
    providers.put("empty", player -> DestinationNode.<NavigationTarget>builder().build());
    providers.put("absent", player -> null);
    providers.put("essentials", player -> player.equals("alex") ? spawn : null);

    assertEquals(
        List.of("essentials", "warps"),
        List.copyOf(DestinationRoots.of(providers, "alex").keySet()));
    assertEquals(List.of("warps"), List.copyOf(DestinationRoots.of(providers, "sam").keySet()));
  }
}
