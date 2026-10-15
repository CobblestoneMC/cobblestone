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
import org.cobblestonemc.plugin.api.DestinationLeaf;
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

    DestinationLeaf<NavigationTarget> seen = adapted.leaves().get("dungeon");
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

    Validity<NavigationTarget> built = adapted.leaves().get("dungeon").build(OptionValues.empty());
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
    assertTrue(adapted.subTrees().get("town").get().strict());
    assertTrue(visited.get());
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
    return new PlatformDestinationTree<>() {
      @Override
      public boolean strict() {
        return strict;
      }

      @Override
      public Map<String, Supplier<? extends PlatformDestinationTree<String, Integer>>> subTrees() {
        return subTrees;
      }

      @Override
      public Map<String, DestinationLeaf<MinecraftDestination<String, Integer>>> leaves() {
        return leaves;
      }
    };
  }
}
