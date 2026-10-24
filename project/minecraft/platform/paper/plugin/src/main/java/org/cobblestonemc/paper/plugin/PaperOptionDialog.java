/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper.plugin;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.cobblestonemc.plugin.destination.DestinationResolver;
import org.cobblestonemc.plugin.destination.NavigationTarget;
import org.cobblestonemc.plugin.menu.MenuAction;
import org.cobblestonemc.plugin.menu.OptionForm;
import org.cobblestonemc.plugin.message.CobblestoneMessages;
import org.cobblestonemc.plugin.message.Messages;

/**
 * Asks a player for a destination's options in a dialog, then navigates there as though they had
 * typed it all.
 *
 * <p>Paper marks its dialog API experimental, so a Paper update may change it under this class.
 */
final class PaperOptionDialog {

  /** The choice that leaves an optional option out; not a value any option takes. */
  private static final String UNSET = "-";

  /** How long the dialog's buttons stay usable, should the player leave it open. */
  private static final Duration LIFETIME = Duration.ofMinutes(10);

  private PaperOptionDialog() {}

  static void show(
      Plugin plugin,
      Player player,
      MenuAction.FillIn fillIn,
      NavigateCommand navigate,
      Messages messages) {
    Locale locale = player.locale();
    List<OptionForm.Field> fields = OptionForm.fields(fillIn.destination());
    List<DialogInput> inputs = new ArrayList<>();
    for (int i = 0; i < fields.size(); i++) {
      inputs.add(input(key(i), fields.get(i), locale, messages));
    }

    ActionButton go =
        ActionButton.builder(messages.renderBare(locale, CobblestoneMessages.MENU_DIALOG_NAVIGATE))
            .action(
                DialogAction.customClick(
                    (response, audience) ->
                        // Run on the player's own thread: navigating builds the destination there.
                        player
                            .getScheduler()
                            .run(
                                plugin,
                                task -> submit(player, fillIn, fields.size(), response, navigate),
                                null),
                    ClickCallback.Options.builder().uses(1).lifetime(LIFETIME).build()))
            .build();
    ActionButton cancel =
        ActionButton.builder(messages.renderBare(locale, CobblestoneMessages.MENU_DIALOG_CANCEL))
            .build();
    Dialog dialog =
        Dialog.create(
            factory ->
                factory
                    .empty()
                    .base(
                        DialogBase.builder(fillIn.title())
                            .canCloseWithEscape(true)
                            .inputs(inputs)
                            .build())
                    .type(DialogType.confirmation(go, cancel)));
    player.showDialog(dialog);
  }

  private static DialogInput input(
      String key, OptionForm.Field field, Locale locale, Messages messages) {
    Component label =
        field.required()
            ? Component.text(field.name())
            : messages.renderBare(locale, CobblestoneMessages.MENU_DIALOG_OPTIONAL, field.name());
    if (field.choices().isEmpty()) {
      return DialogInput.text(key, label).initial(field.initial()).build();
    }
    List<SingleOptionDialogInput.OptionEntry> entries = new ArrayList<>();
    if (!field.required()) {
      entries.add(
          SingleOptionDialogInput.OptionEntry.create(
              UNSET, messages.renderBare(locale, CobblestoneMessages.MENU_DIALOG_UNSET), true));
    }
    for (String choice : field.choices()) {
      entries.add(
          SingleOptionDialogInput.OptionEntry.create(
              choice, Component.text(choice), field.required() && choice.equals(field.initial())));
    }
    return DialogInput.singleOption(key, label, entries).build();
  }

  private static void submit(
      Player player,
      MenuAction.FillIn fillIn,
      int fieldCount,
      DialogResponseView response,
      NavigateCommand navigate) {
    if (!player.isOnline()) {
      return;
    }
    List<String> answers = new ArrayList<>();
    for (int i = 0; i < fieldCount; i++) {
      String answer = response.getText(key(i));
      answers.add(answer == null || answer.equals(UNSET) ? "" : answer);
    }
    switch (DestinationResolver.options(
        fillIn.address(), fillIn.destination(), OptionForm.tokens(answers))) {
      case DestinationResolver.Resolved<NavigationTarget> resolved ->
          navigate.navigate(player, resolved);
      case DestinationResolver.InvalidOptions<NavigationTarget> invalid ->
          navigate.sendUsage(player, invalid);
      default -> {}
    }
  }

  private static String key(int index) {
    return "option_" + index;
  }
}
