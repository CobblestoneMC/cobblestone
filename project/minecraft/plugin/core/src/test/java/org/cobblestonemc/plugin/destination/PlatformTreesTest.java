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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import org.cobblestonemc.DomainRegion;
import org.cobblestonemc.api.Destination;
import org.cobblestonemc.minecraft.MinecraftWorld;
import org.cobblestonemc.minecraft.api.WorldRegion;
import org.cobblestonemc.plugin.api.DestinationEntry;
import org.cobblestonemc.plugin.api.DestinationLeaf;
import org.cobblestonemc.plugin.api.DestinationOption;
import org.cobblestonemc.plugin.api.DestinationTree;
import org.cobblestonemc.plugin.api.MinecraftDestination;
import org.cobblestonemc.plugin.api.OptionValues;
import org.cobblestonemc.plugin.api.Validity;
import org.junit.jupiter.api.Test;

/** An integration's tree, seen in Cobblestone's own terms. */
class PlatformTreesTest {

  /** "Converts" a region by naming it; a region in the world "unloaded" cannot be converted. */
  private final AtomicInteger conversions = new AtomicInteger();

  private final Function<WorldRegion<String, Integer>, Optional<DomainRegion<MinecraftWorld>>>
      regions =
          region -> {
            conversions.incrementAndGet();
            return region.world().equals("unloaded")
                ? Optional.empty()
                : Optional.of(
                    new DomainRegion.Impl<>(
                        null, cell -> false, cell -> cell, () -> "converted " + region));
          };

  @Test
  void leavesDescribeThemselvesAsTheIntegrationDoes() {
    DestinationOption<Integer> level = DestinationOption.integer("level", () -> 1);
    DestinationLeaf<MinecraftDestination<String, Integer>> leaf =
        DestinationLeaf.<MinecraftDestination<String, Integer>>withOptions(
                List.of(level),
                values -> Validity.valid(destination("dungeon " + values.get(level), List::of)))
            .withPermissions("quests.dungeon");
    DestinationTree<NavigationTarget> adapted =
        PlatformTrees.adapt(tree(false, Map.of(), Map.of("dungeon", leaf)), regions);

    DestinationLeaf<NavigationTarget> seen =
        adapted.entries().get("dungeon").destination().orElseThrow();
    assertEquals(List.of("quests.dungeon"), seen.permissions());
    assertEquals(List.of(level), seen.options());

    Validity<NavigationTarget> built = seen.build(OptionValues.of(Map.of(level, 3)));
    Validity.Valid<?> valid = assertInstanceOf(Validity.Valid.class, built);
    NavigationTarget target = (NavigationTarget) valid.destination();
    assertEquals(Component.text("dungeon 3"), target.displayName());
  }

  @Test
  void anInvalidLeafStaysInvalid() {
    Component reason = Component.text("No dungeon today!");
    DestinationTree<NavigationTarget> adapted =
        PlatformTrees.adapt(
            tree(
                false,
                Map.of(),
                Map.of("dungeon", DestinationLeaf.ofValidity(() -> invalid(reason)))),
            regions);

    Validity<NavigationTarget> built =
        adapted.entries().get("dungeon").destination().orElseThrow().build(OptionValues.empty());
    assertEquals(reason, assertInstanceOf(Validity.Invalid.class, built).reason());
  }

  @Test
  void subTreesAreAdaptedOnlyWhenVisited() {
    AtomicBoolean visited = new AtomicBoolean();
    Supplier<DestinationTree<MinecraftDestination<String, Integer>>> child =
        () -> {
          visited.set(true);
          return tree(true, Map.of(), Map.of());
        };
    DestinationTree<NavigationTarget> adapted =
        PlatformTrees.adapt(tree(false, Map.of("town", child), Map.of()), regions);

    assertFalse(visited.get());
    assertTrue(adapted.entries().get("town").subtree().orElseThrow().get().strict());
    assertTrue(visited.get());
  }

  @Test
  void entriesKeepBothRoles() {
    DestinationEntry<MinecraftDestination<String, Integer>> town =
        DestinationEntry.<MinecraftDestination<String, Integer>>builder()
            .destination(() -> destination("riverwood", List::of))
            .subtree(() -> tree(true, Map.of(), Map.of()))
            .build();
    DestinationTree<MinecraftDestination<String, Integer>> root =
        DestinationTree.<MinecraftDestination<String, Integer>>builder()
            .entry("riverwood", town)
            .build();

    DestinationEntry<NavigationTarget> adapted =
        PlatformTrees.adapt(root, regions).entries().get("riverwood");
    assertTrue(adapted.destination().isPresent());
    assertTrue(adapted.subtree().orElseThrow().get().strict());
  }

  @Test
  void anEntryNeedsADestinationOrASubtree() {
    assertThrows(IllegalStateException.class, () -> DestinationEntry.<String>builder().build());
  }

  @Test
  void anEntryIsBothADestinationAndASubtreeOnlyThroughEntry() {
    DestinationLeaf<String> leaf = DestinationLeaf.of(() -> "riverwood");
    Supplier<DestinationTree<String>> subtree = () -> DestinationTree.<String>builder().build();

    DestinationTree<String> node =
        DestinationTree.<String>builder()
            .entry(
                "riverwood", DestinationEntry.<String>builder().destination(leaf).subtree(subtree))
            .build();
    DestinationEntry<String> riverwood = node.entries().get("riverwood");
    assertEquals(Optional.of(leaf), riverwood.destination());
    assertEquals(Optional.of(subtree), riverwood.subtree());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            DestinationTree.<String>builder()
                .destination("riverwood", leaf)
                .subtree("riverwood", subtree));
  }

  @Test
  void aKeyOrAliasIsAddedOnce() {
    DestinationTree.Builder<String> node =
        DestinationTree.<String>builder()
            .destination("market", () -> "market")
            .alias("mkt", "market");
    assertThrows(IllegalArgumentException.class, () -> node.destination("market", () -> "other"));
    assertThrows(
        IllegalArgumentException.class,
        () -> node.subtree("mkt", () -> DestinationTree.<String>builder().build()));
    assertThrows(IllegalArgumentException.class, () -> node.alias("mkt", "market"));
    assertThrows(IllegalArgumentException.class, () -> node.alias("market", "market"));
  }

  @Test
  void aSubtreeFromABuilderIsBuiltOnceWhenAdded() {
    DestinationTree.Builder<String> towns =
        DestinationTree.<String>builder().destination("riverwood", () -> "riverwood");
    DestinationTree<String> node = DestinationTree.<String>builder().subtree("town", towns).build();
    towns.destination("oakvale", () -> "oakvale");

    Supplier<? extends DestinationTree<String>> town =
        node.entries().get("town").subtree().orElseThrow();
    assertSame(town.get(), town.get());
    assertEquals(Set.of("riverwood"), town.get().entries().keySet());
  }

  @Test
  void aTreeKeepsItsEntriesInTheOrderAdded() {
    DestinationTree<String> node =
        DestinationTree.<String>builder()
            .destination("spawn", DestinationLeaf.of(() -> "spawn"))
            .subtree("town", () -> DestinationTree.<String>builder().build())
            .destination("arena", DestinationLeaf.of(() -> "arena"))
            .build();
    assertEquals(List.of("spawn", "town", "arena"), List.copyOf(node.entries().keySet()));
  }

  @Test
  void onlyADestinationTakesAnAlias() {
    DestinationTree.Builder<String> node =
        DestinationTree.<String>builder()
            .subtree("town", () -> DestinationTree.<String>builder().build());
    assertThrows(IllegalArgumentException.class, () -> node.alias("towns", "town"));
  }

  @Test
  void regionsAreConvertedOnEachSearchAndUnloadedOnesDropped() {
    List<WorldRegion<String, Integer>> current = new ArrayList<>();
    current.add(region("overworld"));
    NavigationTarget target =
        PlatformTrees.target(destination("npc", () -> List.copyOf(current)), regions);
    assertEquals(0, conversions.get(), "nothing is converted until a search reads the regions");

    assertEquals(1, target.destination().regions().size());
    current.set(0, region("unloaded"));
    assertTrue(target.destination().regions().isEmpty(), "the NPC moved somewhere unsearchable");
    assertEquals(2, conversions.get());
  }

  @Test
  void requiredOptionsMustComeFirst() {
    DestinationOption<Integer> x = DestinationOption.integer("x", () -> 0);
    DestinationOption<String> world =
        DestinationOption.optional("world", partial -> List.of(), Optional::of);
    assertThrows(
        IllegalArgumentException.class,
        () -> DestinationLeaf.withOptions(List.of(world, x), values -> invalid(Component.empty())));
  }

  private static Validity<MinecraftDestination<String, Integer>> invalid(Component reason) {
    return Validity.invalid(reason);
  }

  private static MinecraftDestination<String, Integer> destination(
      String name, Destination<WorldRegion<String, Integer>> destination) {
    return new MinecraftDestination<>() {
      @Override
      public Destination<WorldRegion<String, Integer>> destination() {
        return destination;
      }

      @Override
      public Component displayName() {
        return Component.text(name);
      }
    };
  }

  private static WorldRegion<String, Integer> region(String world) {
    return new WorldRegion<>() {
      @Override
      public String world() {
        return world;
      }

      @Override
      public boolean contains(Integer location) {
        return false;
      }

      @Override
      public Integer nearestBoundaryLocation(Integer location) {
        return location;
      }
    };
  }

  private static DestinationTree<MinecraftDestination<String, Integer>> tree(
      boolean strict,
      Map<String, Supplier<? extends DestinationTree<MinecraftDestination<String, Integer>>>>
          subTrees,
      Map<String, DestinationLeaf<MinecraftDestination<String, Integer>>> leaves) {
    DestinationTree.Builder<MinecraftDestination<String, Integer>> tree = DestinationTree.builder();
    if (strict) {
      tree.strict();
    }
    leaves.forEach(tree::destination);
    subTrees.forEach(tree::subtree);
    return tree.build();
  }
}
