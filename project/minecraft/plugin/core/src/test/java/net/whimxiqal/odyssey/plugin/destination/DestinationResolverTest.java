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
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.whimxiqal.odyssey.api.Destination;
import net.whimxiqal.odyssey.minecraft.api.WorldRegion;
import net.whimxiqal.odyssey.plugin.api.MinecraftDestination;
import net.whimxiqal.odyssey.plugin.api.PlatformDestinationTree;
import net.whimxiqal.odyssey.plugin.destination.DestinationResolver.Ambiguous;
import net.whimxiqal.odyssey.plugin.destination.DestinationResolver.NotFound;
import net.whimxiqal.odyssey.plugin.destination.DestinationResolver.Resolution;
import net.whimxiqal.odyssey.plugin.destination.DestinationResolver.Resolved;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Addressing (promotion, ambiguity, strict levels, case), permissions, laziness, and tab-completion
 * for the resolver. Tests use arbitrary world/vector types; the resolver never inspects them.
 */
class DestinationResolverTest {

  private static final Predicate<String> ALL = permission -> true;
  private static final Predicate<List<String>> ANY = address -> true;

  /** Counts how many destinations the walk actually built — the cost we care about bounding. */
  private int destinationsBuilt;

  /** Counts how many sub-tree nodes the walk actually built. */
  private int nodesBuilt;

  @BeforeEach
  void reset() {
    destinationsBuilt = 0;
    nodesBuilt = 0;
  }

  // -----------------------------------------------------------------------------------------
  // Resolution
  // -----------------------------------------------------------------------------------------

  @Test
  void resolvesAnyOrderedSubsequenceOfTheAddress() {
    var roots = roots(node("mco").sub(node("warp").leaf("spawn")));

    // The full path, and every ordered subsequence of its ancestors, reach the same destination.
    for (List<String> args :
        List.of(
            List.of("mco", "warp", "spawn"),
            List.of("warp", "spawn"),
            List.of("mco", "spawn"),
            List.of("spawn"))) {
      Resolution<String, Integer> resolution = resolve(roots, args);
      assertInstanceOf(Resolved.class, resolution, args.toString());
      // Whichever form was typed, the canonical address comes back for messages and permissions.
      assertEquals(
          List.of("mco", "warp", "spawn"),
          ((Resolved<String, Integer>) resolution).address(),
          args.toString());
    }
  }

  @Test
  void rejectsReorderedOrIncompletePaths() {
    var roots = roots(node("mco").sub(node("warp").leaf("spawn")));

    assertInstanceOf(NotFound.class, resolve(roots, List.of("warp", "mco", "spawn"))); // reordered
    assertInstanceOf(NotFound.class, resolve(roots, List.of("mco", "warp"))); // no destination
    assertInstanceOf(NotFound.class, resolve(roots, List.of("spawn", "mco"))); // name is not first
    assertInstanceOf(NotFound.class, resolve(roots, List.of())); // nothing typed
    assertInstanceOf(NotFound.class, resolve(roots, List.of("nowhere")));
  }

  @Test
  void matchesCaseInsensitively() {
    var roots = roots(node("mco").sub(node("warp").leaf("Spawn")));

    assertInstanceOf(Resolved.class, resolve(roots, List.of("MCO", "Warp", "spawn")));
    assertInstanceOf(Resolved.class, resolve(roots, List.of("SPAWN")));
  }

  @Test
  void lookalikeKeysCollideRatherThanConfusingPlayers() {
    // Two providers whose roots differ only in case are ambiguous on purpose — a player cannot be
    // expected to tell "quest bounty" and "Quest bounty" apart.
    var roots = roots(node("quest").leaf("bounty"), node("Quest").leaf("bounty"));

    assertInstanceOf(Ambiguous.class, resolve(roots, List.of("quest", "bounty")));
  }

  @Test
  void ambiguousPromotionForcesFullerPath() {
    var roots = roots(node("location").leaf("home"), node("essentials").leaf("home"));

    Resolution<String, Integer> ambiguous = resolve(roots, List.of("home"));
    assertInstanceOf(Ambiguous.class, ambiguous);
    // Alphabetical, not the order the roots happened to be registered in.
    assertEquals(
        List.of(List.of("essentials", "home"), List.of("location", "home")),
        ((Ambiguous<String, Integer>) ambiguous).addresses());

    // Naming the provider disambiguates.
    assertInstanceOf(Resolved.class, resolve(roots, List.of("essentials", "home")));
  }

  @Test
  void strictLevelCannotBeOmitted() {
    var roots = roots(node("towny").sub(node("town").strict().leaf("shire")));

    assertInstanceOf(NotFound.class, resolve(roots, List.of("shire")));
    assertInstanceOf(NotFound.class, resolve(roots, List.of("towny", "shire")));
    assertInstanceOf(Resolved.class, resolve(roots, List.of("town", "shire")));
    assertInstanceOf(Resolved.class, resolve(roots, List.of("towny", "town", "shire")));
  }

  @Test
  void oneKeyMayBeBothADestinationAndALevel() {
    // Towny's shape: "towny resident" is the town itself, "towny resident home" its spawn.
    var roots = roots(node("towny").leaf("resident").sub(node("resident").leaf("home")));

    Resolution<String, Integer> town = resolve(roots, List.of("towny", "resident"));
    assertInstanceOf(Resolved.class, town);
    assertEquals(List.of("towny", "resident"), ((Resolved<String, Integer>) town).address());

    Resolution<String, Integer> spawn = resolve(roots, List.of("towny", "resident", "home"));
    assertInstanceOf(Resolved.class, spawn);
    assertEquals(
        List.of("towny", "resident", "home"), ((Resolved<String, Integer>) spawn).address());
  }

  @Test
  void permissionGatedDestinationsAreHidden() {
    var roots = roots(node("secret").leaf("vault", "odyssey.dest.vault"));

    assertInstanceOf(
        NotFound.class,
        DestinationResolver.resolve(roots, List.of("vault"), permission -> false, ANY));
    assertInstanceOf(
        Resolved.class,
        DestinationResolver.resolve(roots, List.of("vault"), "odyssey.dest.vault"::equals, ANY));
  }

  @Test
  void navigationGateHidesDeniedAddresses() {
    var roots = roots(node("essentials").leaf("home").leaf("bed"));
    Predicate<List<String>> gate = address -> !address.equals(List.of("essentials", "home"));

    assertInstanceOf(
        NotFound.class,
        DestinationResolver.resolve(roots, List.of("essentials", "home"), ALL, gate));
    assertInstanceOf(
        Resolved.class,
        DestinationResolver.resolve(roots, List.of("essentials", "bed"), ALL, gate));
    // Suggestions drop the gated destination too.
    assertEquals(
        List.of("bed"), DestinationResolver.suggest(roots, List.of("essentials", ""), ALL, gate));
  }

  @Test
  void aHiddenTwinLeavesTheOtherUnambiguous() {
    // Two providers both offer "home", but this player may only use one — so the short form works.
    var roots =
        roots(node("location").leaf("home"), node("essentials").leaf("home", "essentials.home"));

    Resolution<String, Integer> resolution =
        DestinationResolver.resolve(roots, List.of("home"), permission -> false, ANY);
    assertInstanceOf(Resolved.class, resolution);
    assertEquals(List.of("location", "home"), ((Resolved<String, Integer>) resolution).address());
    // And the completion offers it, since it is no longer ambiguous.
    assertEquals(
        List.of("home", "location"),
        DestinationResolver.suggest(roots, List.of(), permission -> false, ANY));
  }

  @Test
  void promotionStillResolvesPastAnOversizedLevel() {
    // The suggestion limit is about what is *offered*; a player who knows the name can still use
    // the short form.
    Tree npcs = node("npc");
    for (int i = 0; i < 40; i++) {
      npcs.leaf("npc-" + i);
    }
    var roots = roots(node("citizens").sub(npcs));

    assertInstanceOf(Resolved.class, resolve(roots, List.of("npc-7")));
  }

  @Test
  void resolutionBuildsOnlyTheDestinationsItMatches() {
    Tree warps = node("warp");
    for (int i = 0; i < 40; i++) {
      warps.leaf("warp-" + i);
    }
    var roots = roots(node("mco").sub(warps));

    assertInstanceOf(Resolved.class, resolve(roots, List.of("warp-3")));
    assertEquals(1, destinationsBuilt, "only the destination whose key matched should be built");
  }

  // -----------------------------------------------------------------------------------------
  // Suggestion
  // -----------------------------------------------------------------------------------------

  @Test
  void suggestsTheNextTokenAfterAProviderKey() {
    // The regression: typing "/nav mco " must move on to what lives under "mco", not re-offer it.
    var roots = roots(node("mco").sub(node("warp").leaf("spawn").leaf("shop")));

    assertEquals(List.of("mco", "shop", "spawn", "warp"), suggest(roots, List.of()));
    assertEquals(List.of("mco"), suggest(roots, List.of("mc")));
    assertEquals(List.of("shop", "spawn", "warp"), suggest(roots, List.of("mco", "")));
    assertEquals(List.of("warp"), suggest(roots, List.of("mco", "wa")));
    assertEquals(List.of("shop", "spawn"), suggest(roots, List.of("mco", "warp", "")));
    assertEquals(List.of("spawn"), suggest(roots, List.of("mco", "warp", "sp")));
    // A complete address has no continuation.
    assertEquals(List.of(), suggest(roots, List.of("mco", "warp", "spawn", "")));
    // The promoted forms complete just as well as the canonical one.
    assertEquals(List.of("shop", "spawn"), suggest(roots, List.of("warp", "")));
    assertEquals(List.of("shop", "spawn", "warp"), suggest(roots, List.of("mco", "")));
  }

  @Test
  void suggestionsAreSortedAndCaseInsensitive() {
    var roots = roots(node("MCO").sub(node("Warp").leaf("Spawn").leaf("shop")));

    assertEquals(List.of("MCO", "shop", "Spawn", "Warp"), suggest(roots, List.of()));
    assertEquals(List.of("shop", "Spawn", "Warp"), suggest(roots, List.of("mco", "")));
    assertEquals(List.of("Spawn"), suggest(roots, List.of("mco", "warp", "sPa")));
  }

  @Test
  void aStrictLevelIsNeverPromotedAndNeverWalked() {
    var roots = roots(node("citizens").sub(node("npc").strict().leaf("bob").leaf("ann")));

    // The names stay behind "npc", and finding that out costs nothing below the strict node.
    assertEquals(List.of("citizens", "npc"), suggest(roots, List.of()));
    assertEquals(0, destinationsBuilt, "a strict level's destinations must not be built");

    assertEquals(List.of("npc"), suggest(roots, List.of("citizens", "")));
    assertEquals(List.of("ann", "bob"), suggest(roots, List.of("npc", "")));
    assertEquals(List.of("ann", "bob"), suggest(roots, List.of("citizens", "npc", "")));
  }

  @Test
  void aSmallLevelIsPromotedIntoItsParentsSuggestions() {
    var roots = roots(node("citizens").sub(node("npc").leaf("bob").leaf("ann")));

    assertEquals(List.of("ann", "bob", "citizens", "npc"), suggest(roots, List.of()));
  }

  @Test
  void anOversizedLevelActsStrictForSuggestionsOnly() {
    Tree npcs = node("npc");
    for (int i = 0; i < DestinationResolver.PROMOTION_LIMIT + 4; i++) {
      npcs.leaf("npc-" + i);
    }
    var roots = roots(node("citizens").sub(npcs));

    // Too many to inject at the root, so "npc" behaves as if the provider had marked it strict.
    assertEquals(List.of("citizens", "npc"), suggest(roots, List.of()));
    assertTrue(
        destinationsBuilt <= DestinationResolver.PROMOTION_LIMIT + 1,
        "the walk should stop counting once past the limit, built " + destinationsBuilt);

    // Named explicitly, the level shows everything it has — capping that is the command layer's
    // call, not the resolver's.
    assertEquals(
        DestinationResolver.PROMOTION_LIMIT + 4, suggest(roots, List.of("npc", "")).size());
  }

  @Test
  void anOversizedLevelDoesNotSinkItsSiblings() {
    Tree npcs = node("npc");
    for (int i = 0; i < DestinationResolver.PROMOTION_LIMIT + 4; i++) {
      npcs.leaf("npc-" + i);
    }
    var roots = roots(node("citizens").sub(npcs).sub(node("shop").leaf("forge")));

    // "npc" loses its promotion; "shop" keeps its own.
    assertEquals(List.of("citizens", "forge", "npc", "shop"), suggest(roots, List.of()));
  }

  @Test
  void aLevelWithTooManyChildrenMustBeNamed() {
    Tree warps = node("warp");
    for (int i = 0; i < DestinationResolver.PROMOTION_LIMIT + 1; i++) {
      warps.leaf("warp-" + i);
    }
    var roots = roots(warps);

    // Nothing can be promoted out of a root this wide, so only the root itself is offered.
    assertEquals(List.of("warp"), suggest(roots, List.of()));
    assertEquals(
        DestinationResolver.PROMOTION_LIMIT + 1, suggest(roots, List.of("warp", "")).size());
  }

  @Test
  void anAmbiguousShortcutIsNotSuggested() {
    var roots = roots(node("location").leaf("home").leaf("camp"), node("essentials").leaf("home"));

    // "home" would resolve ambiguously and cannot be repaired by typing more, so it is not offered;
    // the unambiguous "camp" and both provider keys are.
    assertEquals(List.of("camp", "essentials", "location"), suggest(roots, List.of()));
    // Under a provider it is unambiguous again.
    assertEquals(List.of("home"), suggest(roots, List.of("essentials", "")));
    assertEquals(List.of("camp", "home"), suggest(roots, List.of("location", "")));
  }

  @Test
  void anAmbiguousTokenThatLeadsSomewhereIsStillSuggested() {
    // "resident" completes two addresses, but it also opens a level — typing it is progress.
    var roots =
        roots(
            node("towny").leaf("resident").sub(node("resident").leaf("home")),
            node("other").leaf("resident"));

    assertTrue(suggest(roots, List.of()).contains("resident"));
    assertEquals(List.of("home"), suggest(roots, List.of("towny", "resident", "")));
  }

  @Test
  void aDeadEndLevelIsNotSuggested() {
    var roots = roots(node("empty"), node("mco").leaf("spawn"));

    assertEquals(List.of("mco", "spawn"), suggest(roots, List.of()));
  }

  @Test
  void tokensAreDeduplicatedAcrossProviders() {
    // Both providers offer a "warp" level; the token appears once.
    var roots =
        roots(
            node("mco").sub(node("warp").leaf("spawn")),
            node("other").sub(node("warp").leaf("market")));

    assertEquals(List.of("market", "mco", "other", "spawn", "warp"), suggest(roots, List.of()));
    assertEquals(List.of("market", "spawn"), suggest(roots, List.of("warp", "")));
  }

  // -----------------------------------------------------------------------------------------
  // Fixtures
  // -----------------------------------------------------------------------------------------

  private static Resolution<String, Integer> resolve(
      Map<String, PlatformDestinationTree<String, Integer>> roots, List<String> args) {
    return DestinationResolver.resolve(roots, args, ALL, ANY);
  }

  private static List<String> suggest(
      Map<String, PlatformDestinationTree<String, Integer>> roots, List<String> args) {
    return DestinationResolver.suggest(roots, args, ALL, ANY);
  }

  private static Map<String, PlatformDestinationTree<String, Integer>> roots(Tree... trees) {
    // Sorted, as the command builds it: root order must not depend on registration order.
    Map<String, PlatformDestinationTree<String, Integer>> out = new TreeMap<>();
    for (Tree tree : trees) {
      out.put(tree.key, tree.build());
    }
    return out;
  }

  private Tree node(String key) {
    return new Tree(key);
  }

  /** A test tree whose children count how often they are actually materialized. */
  private final class Tree {

    private final String key;
    private boolean strict;
    private final Map<String, Supplier<PlatformDestinationTree<String, Integer>>> subs =
        new LinkedHashMap<>();
    private final Map<String, Supplier<MinecraftDestination<String, Integer>>> leaves =
        new LinkedHashMap<>();

    private Tree(String key) {
      this.key = key;
    }

    Tree strict() {
      this.strict = true;
      return this;
    }

    Tree leaf(String leafKey, String... permissions) {
      leaves.put(
          leafKey,
          () -> {
            destinationsBuilt++;
            Destination<WorldRegion<String, Integer>> destination = List::of;
            return new SimpleMinecraftDestination<>(
                destination, Component.text(leafKey), List.of(permissions));
          });
      return this;
    }

    Tree sub(Tree child) {
      subs.put(
          child.key,
          () -> {
            nodesBuilt++;
            return child.build();
          });
      return this;
    }

    PlatformDestinationTree<String, Integer> build() {
      return new SimplePlatformDestinationTree<>(strict, subs, leaves);
    }
  }
}
