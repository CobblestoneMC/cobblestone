/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.format;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The block states one chunk column contains, in the order they were first seen.
 *
 * <p><b>Column-local, not capture-global.</b> A capture-wide palette would compress a little
 * better, but every column file would then depend on a shared file that changes whenever <i>any</i>
 * column is re-captured — which is exactly the diff-locality this format exists to buy. A column's
 * palette runs to a few dozen entries, so the saving was never large.
 *
 * <p>Entries are the canonical block state strings Minecraft itself prints, e.g. {@code
 * minecraft:oak_door[facing=east,half=lower,open=false]}. No traits are stored here; they live in
 * the capture's {@code traits.json}, written from the platform's real block implementation, so that
 * changing trait logic never invalidates captured block data.
 */
public final class BlockPalette {

  private final List<String> states;
  private final Map<String, Integer> indices;

  private BlockPalette(List<String> states, Map<String, Integer> indices) {
    this.states = states;
    this.indices = indices;
  }

  /**
   * Returns a palette over the given states, which must begin with {@link Sbc#AIR}.
   *
   * @param states the block state strings, in index order
   * @return the palette
   * @throws CaptureFormatException if the states are empty, do not start with air, or repeat
   */
  public static BlockPalette of(List<String> states) throws CaptureFormatException {
    if (states.isEmpty()) {
      throw new CaptureFormatException("palette is empty; index 0 must be " + Sbc.AIR);
    }
    if (!Sbc.AIR.equals(states.get(0))) {
      throw new CaptureFormatException(
          "palette index 0 is '" + states.get(0) + "'; must be " + Sbc.AIR);
    }
    Map<String, Integer> indices = new HashMap<>(states.size() * 2);
    for (int i = 0; i < states.size(); i++) {
      String state = Objects.requireNonNull(states.get(i), "palette entry " + i);
      if (indices.putIfAbsent(state, i) != null) {
        throw new CaptureFormatException(
            "palette entry '" + state + "' appears at both " + indices.get(state) + " and " + i);
      }
    }
    return new BlockPalette(List.copyOf(states), Map.copyOf(indices));
  }

  /**
   * Returns the block state at the given index.
   *
   * @param index the palette index
   * @return the block state string
   * @throws IndexOutOfBoundsException if the index is not in the palette
   */
  public String state(int index) {
    return states.get(index);
  }

  /**
   * Returns the index of the given block state, or {@code -1} if this palette does not hold it.
   *
   * @param state the block state string
   * @return the index, or {@code -1}
   */
  public int indexOf(String state) {
    Integer index = indices.get(state);
    return index == null ? -1 : index;
  }

  /**
   * Returns the number of entries.
   *
   * @return the palette size
   */
  public int size() {
    return states.size();
  }

  /**
   * Returns the entries in index order.
   *
   * @return an immutable list of block state strings
   */
  public List<String> states() {
    return states;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof BlockPalette other && states.equals(other.states);
  }

  @Override
  public int hashCode() {
    return states.hashCode();
  }

  @Override
  public String toString() {
    return "BlockPalette" + states;
  }

  /**
   * Accumulates block states in first-seen order, seeded with air at index 0.
   *
   * <p>First-seen rather than sorted: a writer that walks a column in a fixed order produces a
   * deterministic palette without paying for a sort, and determinism is what keeps re-captures
   * byte-identical.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /** Builds a palette, assigning indices in first-seen order. */
  public static final class Builder {

    private final List<String> states = new ArrayList<>();
    private final Map<String, Integer> indices = new HashMap<>();

    private Builder() {
      add(Sbc.AIR);
    }

    /**
     * Returns the index of {@code state}, adding it to the palette if it is new.
     *
     * @param state the block state string
     * @return its palette index
     */
    public int add(String state) {
      Objects.requireNonNull(state, "state");
      Integer existing = indices.get(state);
      if (existing != null) {
        return existing;
      }
      int index = states.size();
      states.add(state);
      indices.put(state, index);
      return index;
    }

    /**
     * Returns the number of entries added so far.
     *
     * @return the palette size
     */
    public int size() {
      return states.size();
    }

    /**
     * Builds the palette.
     *
     * @return the palette
     */
    public BlockPalette build() {
      return new BlockPalette(List.copyOf(states), Map.copyOf(indices));
    }
  }
}
