/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.kyori.adventure.text.Component;

/**
 * How a destination, or a level of them, is shown in the navigation menu: the name on its item, the
 * lines of description beneath it, and the item itself. Every part is optional — the menu falls
 * back to the entry's key and a default item — so an integration can say as much or as little as it
 * likes.
 *
 * <p>Immutable; the {@code with…} methods return a changed copy.
 */
public final class Display {

  private static final Display NONE = new Display(null, List.of(), null);

  private final Component label;
  private final List<Component> description;
  private final Icon icon;

  private Display(Component label, List<Component> description, Icon icon) {
    this.label = label;
    this.description = List.copyOf(description);
    this.icon = icon;
  }

  /**
   * A display that says nothing, leaving everything to the menu's defaults.
   *
   * @return the empty display
   */
  public static Display none() {
    return NONE;
  }

  /**
   * A display that only chooses the item.
   *
   * @param icon the item
   * @return the display
   */
  public static Display of(Icon icon) {
    return NONE.withIcon(icon);
  }

  /**
   * The name shown on the item, if not the entry's key.
   *
   * @return the label
   */
  public Optional<Component> label() {
    return Optional.ofNullable(label);
  }

  /**
   * The lines shown beneath the name.
   *
   * @return the description, possibly empty
   */
  public List<Component> description() {
    return description;
  }

  /**
   * The item shown, if not the menu's default.
   *
   * @return the icon
   */
  public Optional<Icon> icon() {
    return Optional.ofNullable(icon);
  }

  /**
   * Returns a copy with the given label.
   *
   * @param label the label, or {@code null} to use the entry's key
   * @return the copy
   */
  public Display withLabel(Component label) {
    return new Display(label, description, icon);
  }

  /**
   * Returns a copy with the given description.
   *
   * @param description the lines
   * @return the copy
   */
  public Display withDescription(List<Component> description) {
    return new Display(label, Objects.requireNonNull(description, "description"), icon);
  }

  /**
   * Returns a copy with the given icon.
   *
   * @param icon the icon, or {@code null} to use the menu's default
   * @return the copy
   */
  public Display withIcon(Icon icon) {
    return new Display(label, description, icon);
  }

  /**
   * Returns a copy that fills in whatever this display leaves out from {@code fallback}.
   *
   * @param fallback the display to fall back on
   * @return the combined display
   */
  public Display or(Display fallback) {
    return new Display(
        label != null ? label : fallback.label,
        description.isEmpty() ? fallback.description : description,
        icon != null ? icon : fallback.icon);
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof Display that
        && Objects.equals(label, that.label)
        && description.equals(that.description)
        && Objects.equals(icon, that.icon);
  }

  @Override
  public int hashCode() {
    return Objects.hash(label, description, icon);
  }

  @Override
  public String toString() {
    return "Display[label=" + label + ", description=" + description + ", icon=" + icon + "]";
  }
}
