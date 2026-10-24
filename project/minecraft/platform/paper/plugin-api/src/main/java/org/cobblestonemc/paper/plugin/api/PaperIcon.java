/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper.plugin.api;

import java.util.Objects;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;
import org.cobblestonemc.plugin.api.Icon;

/**
 * An {@link Icon} that is a Paper item stack, for an integration that wants full control over what
 * the navigation menu shows — a custom model, a dyed leather piece, an enchantment glint. The menu
 * shows the stack as it is, with Cobblestone's own name and lore put over it.
 */
public final class PaperIcon implements Icon {

  private final ItemStack stack;

  private PaperIcon(ItemStack stack) {
    this.stack = Objects.requireNonNull(stack, "stack").clone();
  }

  /**
   * An icon showing the given stack (a copy of it: changing the stack afterward does not change the
   * icon).
   *
   * @param stack the stack
   * @return the icon
   */
  public static Icon of(ItemStack stack) {
    return new PaperIcon(stack);
  }

  /**
   * An icon showing one of the given item.
   *
   * @param material the item
   * @return the icon
   */
  public static Icon of(Material material) {
    return new PaperIcon(ItemStack.of(material));
  }

  /**
   * An icon showing the item that places the given block.
   *
   * @param block the block
   * @return the icon
   */
  public static Icon of(BlockData block) {
    return of(block.getPlacementMaterial());
  }

  /**
   * The stack this icon shows.
   *
   * @return a copy of the stack
   */
  public ItemStack stack() {
    return stack.clone();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof PaperIcon that && stack.equals(that.stack);
  }

  @Override
  public int hashCode() {
    return stack.hashCode();
  }

  @Override
  public String toString() {
    return "PaperIcon[" + stack + "]";
  }
}
