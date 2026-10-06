/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.sidebar;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.cobblestonemc.plugin.trip.TripLabel;
import org.junit.jupiter.api.Test;

/** How {@link SidebarLabels} shortens a player's trip labels against each other. */
class SidebarLabelsTest {

  private static TripLabel address(String... keys) {
    return TripLabel.address(List.of(keys));
  }

  @Test
  void aLoneTripIsJustItsDestinationKey() {
    assertEquals(
        List.of("home"),
        SidebarLabels.shorten(List.of(address("cobblestone", "location", "private", "home"))));
  }

  @Test
  void distinctDestinationKeysNeedNothingMore() {
    assertEquals(
        List.of("home", "caves"),
        SidebarLabels.shorten(
            List.of(
                address("cobblestone", "location", "private", "home"),
                address("cobblestone", "location", "private", "caves"))));
  }

  @Test
  void sharedDestinationKeysKeepTheKeyThatDiffers() {
    assertEquals(
        List.of("private home", "global home"),
        SidebarLabels.shorten(
            List.of(
                address("cobblestone", "location", "private", "home"),
                address("cobblestone", "location", "global", "home"))));
  }

  @Test
  void skipsSharedAncestorsRatherThanTakingASuffix() {
    // "npc bob" names both; "citizens bob" is as short as it gets.
    assertEquals(
        List.of("citizens bob", "quests bob"),
        SidebarLabels.shorten(
            List.of(address("citizens", "npc", "bob"), address("quests", "npc", "bob"))));
  }

  @Test
  void prefersKeysNearerTheDestinationAmongEquallyShortChoices() {
    assertEquals(
        List.of("b home", "d home"),
        SidebarLabels.shorten(List.of(address("a", "b", "home"), address("c", "d", "home"))));
  }

  @Test
  void anAddressThatIsAnotherAddressesSuffixIsShownInFull() {
    assertEquals(
        List.of("home", "towny home"),
        SidebarLabels.shorten(List.of(address("home"), address("towny", "home"))));
  }

  @Test
  void matchingIsCaseInsensitive() {
    assertEquals(
        List.of("a Home", "b home"),
        SidebarLabels.shorten(List.of(address("a", "Home"), address("b", "home"))));
  }

  @Test
  void apiLabelsAreShownAsGivenButStillBlockAShorterAddress() {
    assertEquals(
        List.of("Lost Lantern", "location home", "home"),
        SidebarLabels.shorten(
            List.of(
                TripLabel.of("Lost Lantern"), address("location", "home"), TripLabel.of("home"))));
  }

  @Test
  void identicalAddressesFallBackToTheFullText() {
    assertEquals(
        List.of("a home", "a home"),
        SidebarLabels.shorten(List.of(address("a", "home"), address("a", "home"))));
  }
}
