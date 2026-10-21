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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import org.cobblestonemc.DomainRegion;
import org.cobblestonemc.api.Destination;
import org.cobblestonemc.minecraft.MinecraftWorld;
import org.cobblestonemc.minecraft.api.WorldRegion;
import org.cobblestonemc.plugin.api.DestinationChild;
import org.cobblestonemc.plugin.api.DestinationLeaf;
import org.cobblestonemc.plugin.api.DestinationNode;
import org.cobblestonemc.plugin.api.DestinationOption;
import org.cobblestonemc.plugin.api.MinecraftDestination;
import org.cobblestonemc.plugin.api.OptionValues;
import org.cobblestonemc.plugin.api.PlatformDestinationTree;
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
    DestinationNode<NavigationTarget> adapted =
        PlatformTrees.adapt(tree(false, Map.of(), Map.of("dungeon", leaf)), regions);

    DestinationLeaf<NavigationTarget> seen =
        adapted.children().get("dungeon").destination().orElseThrow();
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
    DestinationNode<NavigationTarget> adapted =
        PlatformTrees.adapt(
            tree(
                false,
                Map.of(),
                Map.of("dungeon", DestinationLeaf.ofValidity(() -> invalid(reason)))),
            regions);

    Validity<NavigationTarget> built =
        adapted.children().get("dungeon").destination().orElseThrow().build(OptionValues.empty());
    assertEquals(reason, assertInstanceOf(Validity.Invalid.class, built).reason());
  }

  @Test
  void subTreesAreAdaptedOnlyWhenVisited() {
    AtomicBoolean visited = new AtomicBoolean();
    Supplier<PlatformDestinationTree<String, Integer>> child =
        () -> {
          visited.set(true);
          return tree(true, Map.of(), Map.of());
        };
    DestinationNode<NavigationTarget> adapted =
        PlatformTrees.adapt(tree(false, Map.of("town", child), Map.of()), regions);

    assertFalse(visited.get());
    assertTrue(adapted.children().get("town").children().orElseThrow().get().strict());
    assertTrue(visited.get());
  }

  @Test
  void entriesKeepBothRoles() {
    DestinationChild<MinecraftDestination<String, Integer>> town =
        DestinationChild.<MinecraftDestination<String, Integer>>builder()
            .destination(() -> destination("riverwood", List::of))
            .children(() -> tree(true, Map.of(), Map.of()))
            .build();
    PlatformDestinationTree<String, Integer> root =
        new PlatformDestinationTree<>() {
          @Override
          public boolean strict() {
            return false;
          }

          @Override
          public Map<String, DestinationChild<MinecraftDestination<String, Integer>>> children() {
            return Map.of("riverwood", town);
          }
        };

    DestinationChild<NavigationTarget> adapted =
        PlatformTrees.adapt(root, regions).children().get("riverwood");
    assertTrue(adapted.destination().isPresent());
    assertTrue(adapted.children().orElseThrow().get().strict());
  }

  @Test
  void aChildNeedsADestinationOrChildren() {
    assertThrows(IllegalStateException.class, () -> DestinationChild.<String>builder().build());
  }

  @Test
  void aDestinationAndChildrenUnderOneKeyMakeOneEntryWhicheverComesFirst() {
    DestinationLeaf<String> leaf = DestinationLeaf.of(() -> "riverwood");
    Supplier<DestinationNode<String>> level = () -> DestinationNode.<String>builder().build();

    for (DestinationNode<String> node :
        List.of(
            DestinationNode.<String>builder()
                .destination("riverwood", leaf)
                .children("riverwood", level)
                .build(),
            DestinationNode.<String>builder()
                .children("riverwood", level)
                .destination("riverwood", leaf)
                .build())) {
      DestinationChild<String> riverwood = node.children().get("riverwood");
      assertEquals(1, node.children().size());
      assertEquals(Optional.of(leaf), riverwood.destination());
      assertEquals(Optional.of(level), riverwood.children());
    }
  }

  @Test
  void aLevelKeepsItsEntriesInTheOrderAdded() {
    DestinationNode<String> node =
        DestinationNode.<String>builder()
            .destination("spawn", DestinationLeaf.of(() -> "spawn"))
            .children("town", () -> DestinationNode.<String>builder().build())
            .destination("arena", DestinationLeaf.of(() -> "arena"))
            .build();
    assertEquals(List.of("spawn", "town", "arena"), List.copyOf(node.children().keySet()));
  }

  @Test
  void onlyADestinationTakesAnAlias() {
    DestinationNode.Builder<String> node =
        DestinationNode.<String>builder()
            .children("town", () -> DestinationNode.<String>builder().build());
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

  private static PlatformDestinationTree<String, Integer> tree(
      boolean strict,
      Map<String, Supplier<? extends PlatformDestinationTree<String, Integer>>> subTrees,
      Map<String, DestinationLeaf<MinecraftDestination<String, Integer>>> leaves) {
    DestinationNode.Builder<MinecraftDestination<String, Integer>> node = DestinationNode.builder();
    leaves.forEach(node::destination);
    subTrees.forEach(node::children);
    Map<String, DestinationChild<MinecraftDestination<String, Integer>>> children =
        node.build().children();
    return new PlatformDestinationTree<>() {
      @Override
      public boolean strict() {
        return strict;
      }

      @Override
      public Map<String, DestinationChild<MinecraftDestination<String, Integer>>> children() {
        return children;
      }
    };
  }
}
