/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.menu;

import java.util.ArrayList;
import java.util.List;
import org.cobblestonemc.plugin.api.DestinationLeaf;
import org.cobblestonemc.plugin.api.DestinationOption;

/**
 * Turns a destination's {@linkplain DestinationLeaf#options() options} into the fields of a form
 * that asks for them — a dialog, where the platform has one — and the form's answers back into the
 * tokens {@code /navigate} would have been typed with. Platform-neutral, so every platform asks
 * alike.
 */
public final class OptionForm {

  /**
   * The most suggestions an option may have to be asked as a choice among them rather than as text,
   * beyond which the list is too long to be a help.
   */
  static final int MAX_CHOICES = 32;

  /**
   * One field of the form.
   *
   * @param name the option's name
   * @param required whether it must be answered
   * @param choices the answers to choose among, or empty to ask for text
   * @param initial the text to start with (for a text field), or the choice to start on
   */
  public record Field(String name, boolean required, List<String> choices, String initial) {
    /** Canonical constructor; copies the choices. */
    public Field {
      choices = List.copyOf(choices);
    }
  }

  private OptionForm() {}

  /**
   * The fields that ask for a destination's options, in order. An option with a handful of
   * suggestions (the worlds, say) is a choice among them; one with a single suggestion (a
   * coordinate, hinting where the player stands) is text that starts as that suggestion.
   *
   * @param destination the destination
   * @return the fields
   */
  public static List<Field> fields(DestinationLeaf<?> destination) {
    List<Field> fields = new ArrayList<>();
    for (DestinationOption<?> option : destination.options()) {
      List<String> suggestions = option.suggest("");
      boolean choice = suggestions.size() > 1 && suggestions.size() <= MAX_CHOICES;
      String initial = "";
      if (choice && option.required()) {
        initial = suggestions.getFirst();
      } else if (suggestions.size() == 1) {
        initial = suggestions.getFirst();
      }
      fields.add(
          new Field(option.name(), option.required(), choice ? suggestions : List.of(), initial));
    }
    return List.copyOf(fields);
  }

  /**
   * The tokens a form's answers stand for, in order, trimmed. Blank answers at the end are left
   * off, so an optional option left blank takes its default. Options are positional, so a blank
   * answer before one that is filled in cannot be left off: it stays, as an empty token, and the
   * destination reports it with its usage rather than the later answer being dropped.
   *
   * @param answers the answers, one per field, in order
   * @return the tokens
   */
  public static List<String> tokens(List<String> answers) {
    List<String> tokens = new ArrayList<>();
    for (String answer : answers) {
      tokens.add(answer == null ? "" : answer.trim());
    }
    while (!tokens.isEmpty() && tokens.getLast().isEmpty()) {
      tokens.removeLast();
    }
    return List.copyOf(tokens);
  }

  /**
   * Whether a destination must ask before it can be navigated to — whether any of its options is
   * required. One whose options are all optional is navigated to with their defaults.
   *
   * @param destination the destination
   * @return {@code true} if it must ask
   */
  public static boolean asks(DestinationLeaf<?> destination) {
    return destination.options().stream().anyMatch(DestinationOption::required);
  }
}
