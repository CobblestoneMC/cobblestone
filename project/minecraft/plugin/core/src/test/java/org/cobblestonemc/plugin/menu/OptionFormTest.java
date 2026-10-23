/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.cobblestonemc.plugin.api.DestinationLeaf;
import org.cobblestonemc.plugin.api.DestinationOption;
import org.junit.jupiter.api.Test;

class OptionFormTest {

  @Test
  void blankAnswersAtTheEndAreLeftOff() {
    assertEquals(List.of("1", "2"), OptionForm.tokens(Arrays.asList(" 1 ", "2", "", null)));
    assertEquals(List.of(), OptionForm.tokens(List.of("", " ")));
  }

  @Test
  void aBlankAnswerBeforeAFilledOneIsKeptRatherThanTheLaterOneDropped() {
    assertEquals(List.of("1", "", "nether"), OptionForm.tokens(List.of("1", " ", "nether")));
  }

  @Test
  void aFewSuggestionsAreAChoiceAndOneIsAStartingText() {
    List<String> tooMany =
        IntStream.rangeClosed(0, OptionForm.MAX_CHOICES).mapToObj(Integer::toString).toList();
    DestinationLeaf<String> leaf =
        DestinationLeaf.withOptions(
            List.of(
                option("world", true, List.of("overworld", "nether")),
                option("x", true, List.of("12")),
                option("many", true, tooMany),
                option("note", false, List.of())),
            values -> null);

    List<OptionForm.Field> fields = OptionForm.fields(leaf);
    assertEquals(
        new OptionForm.Field("world", true, List.of("overworld", "nether"), "overworld"),
        fields.get(0));
    assertEquals(new OptionForm.Field("x", true, List.of(), "12"), fields.get(1));
    assertEquals(List.of(), fields.get(2).choices(), "too many to choose among");
    assertEquals(new OptionForm.Field("note", false, List.of(), ""), fields.get(3));
  }

  @Test
  void onlyARequiredOptionMustBeAskedFor() {
    assertTrue(
        OptionForm.asks(
            DestinationLeaf.withOptions(List.of(option("x", true, List.of())), values -> null)));
    assertFalse(
        OptionForm.asks(
            DestinationLeaf.withOptions(
                List.of(option("world", false, List.of())), values -> null)));
  }

  private static DestinationOption<String> option(
      String name, boolean required, List<String> suggestions) {
    return required
        ? DestinationOption.required(name, partial -> suggestions, Optional::of)
        : DestinationOption.optional(name, partial -> suggestions, Optional::of);
  }
}
