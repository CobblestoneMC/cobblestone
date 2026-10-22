/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge.plugin.api;

import java.util.Objects;
import org.cobblestonemc.plugin.api.Icon;
import org.spongepowered.api.block.BlockState;
import org.spongepowered.api.item.ItemType;
import org.spongepowered.api.item.inventory.ItemStack;
import org.spongepowered.api.item.inventory.ItemStackSnapshot;

/**
 * An {@link Icon} that is a Sponge item stack, for an integration that wants full control over what
 * the navigation menu shows — a custom model, a dyed leather piece, an enchantment glint. The menu
 * shows the stack as it is, with Cobblestone's own name and lore put over it.
 */
public final class SpongeIcon implements Icon {

  private final ItemStackSnapshot stack;

  private SpongeIcon(ItemStackSnapshot stack) {
    this.stack = Objects.requireNonNull(stack, "stack");
  }

  /**
   * An icon showing the given stack.
   *
   * @param stack the stack
   * @return the icon
   */
  public static Icon of(ItemStackSnapshot stack) {
    return new SpongeIcon(stack);
  }

  /**
   * An icon showing the given stack (a copy of it: changing the stack afterward does not change the
   * icon).
   *
   * @param stack the stack
   * @return the icon
   */
  public static Icon of(ItemStack stack) {
    return new SpongeIcon(stack.asImmutable());
  }

  /**
   * An icon showing one of the given item.
   *
   * @param type the item
   * @return the icon
   */
  public static Icon of(ItemType type) {
    return of(ItemStack.of(type));
  }

  /**
   * An icon showing the item that places the given block.
   *
   * @param block the block
   * @return the icon
   */
  public static Icon of(BlockState block) {
    return of(ItemStack.builder().fromBlockState(block).build());
  }

  /**
   * The stack this icon shows.
   *
   * @return the stack
   */
  public ItemStackSnapshot stack() {
    return stack;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof SpongeIcon that && stack.equals(that.stack);
  }

  @Override
  public int hashCode() {
    return stack.hashCode();
  }

  @Override
  public String toString() {
    return "SpongeIcon[" + stack + "]";
  }
}
