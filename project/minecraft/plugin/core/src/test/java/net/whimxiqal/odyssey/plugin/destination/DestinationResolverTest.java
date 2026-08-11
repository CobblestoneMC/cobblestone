/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.plugin.destination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.whimxiqal.odyssey.api.Destination;
import net.whimxiqal.odyssey.minecraft.api.WorldRegion;
import net.whimxiqal.odyssey.plugin.api.DestinationTree;
import net.whimxiqal.odyssey.plugin.api.MinecraftDestination;
import net.whimxiqal.odyssey.plugin.destination.DestinationResolver.Ambiguous;
import net.whimxiqal.odyssey.plugin.destination.DestinationResolver.NotFound;
import net.whimxiqal.odyssey.plugin.destination.DestinationResolver.Resolution;
import net.whimxiqal.odyssey.plugin.destination.DestinationResolver.Resolved;
import org.junit.jupiter.api.Test;

/** Name-promotion, ambiguity, strict levels, permissions, and tab-completion for the resolver. */
class DestinationResolverTest {

  // Tests use arbitrary world/vector types; the resolver never inspects them.
  private static final Predicate<String> ALL = permission -> true;

  private static MinecraftDestination<String, Integer> dest(String... permissions) {
    Destination<WorldRegion<String, Integer>> destination = List::of;
    return new SimpleMinecraftDestination<>(destination, Component.text("x"), List.of(permissions));
  }

  private static DestinationTree<String, Integer> leafTree(
      String key, boolean strict, String... leafKeys) {
    Map<String, Supplier<MinecraftDestination<String, Integer>>> leaves = new LinkedHashMap<>();
    for (String leafKey : leafKeys) {
      leaves.put(leafKey, DestinationResolverTest::dest);
    }
    return new SimpleDestinationTree<>(key, strict, Map.of(), leaves);
  }

  @Test
  void resolvesExactAndPromotedPaths() {
    List<DestinationTree<String, Integer>> roots = List.of(leafTree("waypoint", false, "home"));

    assertInstanceOf(Resolved.class, DestinationResolver.resolve(roots, List.of("waypoint", "home"), ALL));
    // Promotion: the non-strict "waypoint" level may be omitted.
    Resolution<String, Integer> promoted = DestinationResolver.resolve(roots, List.of("home"), ALL);
    assertInstanceOf(Resolved.class, promoted);
    assertEquals(List.of("waypoint", "home"), ((Resolved<String, Integer>) promoted).address());
  }

  @Test
  void ambiguousPromotionForcesFullerPath() {
    List<DestinationTree<String, Integer>> roots = List.of(
        leafTree("waypoint", false, "home"),
        leafTree("essentials", false, "home"));

    Resolution<String, Integer> ambiguous = DestinationResolver.resolve(roots, List.of("home"), ALL);
    assertInstanceOf(Ambiguous.class, ambiguous);
    assertEquals(2, ((Ambiguous<String, Integer>) ambiguous).addresses().size());

    // Naming the provider disambiguates.
    assertInstanceOf(Resolved.class,
        DestinationResolver.resolve(roots, List.of("essentials", "home"), ALL));
  }

  @Test
  void strictLevelCannotBeOmitted() {
    List<DestinationTree<String, Integer>> roots = List.of(leafTree("towny", true, "spawn"));

    assertInstanceOf(NotFound.class, DestinationResolver.resolve(roots, List.of("spawn"), ALL));
    assertInstanceOf(Resolved.class,
        DestinationResolver.resolve(roots, List.of("towny", "spawn"), ALL));
  }

  @Test
  void permissionGatedDestinationsAreHidden() {
    Map<String, Supplier<MinecraftDestination<String, Integer>>> leaves = new LinkedHashMap<>();
    leaves.put("vault", () -> dest("odyssey.dest.vault"));
    List<DestinationTree<String, Integer>> roots =
        List.of(new SimpleDestinationTree<>("secret", false, Map.of(), leaves));

    assertInstanceOf(NotFound.class,
        DestinationResolver.resolve(roots, List.of("vault"), permission -> false));
    assertInstanceOf(Resolved.class,
        DestinationResolver.resolve(roots, List.of("vault"), "odyssey.dest.vault"::equals));
  }

  @Test
  void navigationGateHidesDeniedAddresses() {
    List<DestinationTree<String, Integer>> roots = List.of(leafTree("essentials", false, "home", "bed"));
    Predicate<List<String>> gate = address -> !address.equals(List.of("essentials", "home"));

    assertInstanceOf(NotFound.class,
        DestinationResolver.resolve(roots, List.of("essentials", "home"), ALL, gate));
    assertInstanceOf(Resolved.class,
        DestinationResolver.resolve(roots, List.of("essentials", "bed"), ALL, gate));
    // Suggestions also drop the gated leaf.
    assertEquals(List.of("bed"), DestinationResolver.suggest(roots, List.of("essentials", ""), ALL, gate));
  }

  @Test
  void suggestsPromotedNamesAndProviderKeys() {
    List<DestinationTree<String, Integer>> roots = List.of(
        leafTree("waypoint", false, "home", "camp"),
        leafTree("essentials", false, "bed"));

    List<String> top = DestinationResolver.suggest(roots, List.of(), ALL);
    assertTrue(top.containsAll(List.of("waypoint", "essentials", "home", "camp", "bed")), top.toString());

    // After naming a provider, only that provider's leaves complete.
    assertEquals(List.of("bed"), DestinationResolver.suggest(roots, List.of("essentials", ""), ALL));
    // Partial prefix filters.
    assertEquals(List.of("camp"), DestinationResolver.suggest(roots, List.of("ca"), ALL));
  }
}
