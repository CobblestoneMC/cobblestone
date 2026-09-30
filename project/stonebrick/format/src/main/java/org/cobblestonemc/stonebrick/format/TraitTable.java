/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.format;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.jetbrains.annotations.Nullable;

/**
 * Maps every block state a capture contains to the traits a search reads from it.
 *
 * <p>One table per capture directory, written by the copier from the platform's real block
 * implementation — so a benchmark's blocks answer exactly as a live server's would, with no second
 * trait table to drift out of step.
 *
 * <p><b>Stored as sorted TSV, not JSON.</b> The file is version-controlled and is regenerated
 * whenever trait logic changes, so what matters most is that a regeneration produces a diff a human
 * can read: sorted keys and one state per line mean the review shows precisely which blocks changed
 * and how. JSON would wrap the same data in punctuation and, with any ordinary writer, in an
 * ordering nobody controls. It also keeps this module dependency-free, which is the property that
 * lets the benchmark platform read a capture with no server and no third-party jar present.
 *
 * <pre>
 *   #stonebrick-traits 1
 *   #state	bits	breakTimeSeconds	speedFactor	damagePerSecond
 *   minecraft:air	0x3f0000	Infinity	1.0	0.0
 * </pre>
 */
public final class TraitTable {

  /**
   * The header, carrying a version that is bumped whenever the trait bits change.
   *
   * <p>A new bit changes what every existing row means without changing how any of them parse, so a
   * stale table reads cleanly and answers wrongly -- soul sand that does not know it is soul sand,
   * and a coarse layer that prices a whole biome as ordinary ground. Refusing to read an old table
   * is the only way that failure gets noticed.
   *
   * <p>Held at 1 while nothing has shipped: a capture is machine-local and regenerable, so a change
   * to the bits is answered by deleting the corpus and re-capturing rather than by growing a
   * version history. ⚠️ That only works if the corpus really is deleted -- a table left behind from
   * before such a change carries this same header and will be read as current.
   */
  private static final String HEADER = "#stonebrick-traits 1";

  private static final String COLUMNS =
      "#state\tbits\tbreakTimeSeconds\tspeedFactor\tdamagePerSecond";

  /** The file name a trait table always has inside a capture directory. */
  public static final String FILE_NAME = "traits.tsv";

  private final Map<String, BlockTraits> traits;

  private TraitTable(Map<String, BlockTraits> traits) {
    this.traits = traits;
  }

  /**
   * Returns a table over the given mapping.
   *
   * @param traits block state string to traits
   * @return the table
   */
  public static TraitTable of(Map<String, BlockTraits> traits) {
    return new TraitTable(Map.copyOf(traits));
  }

  /**
   * Returns the traits for a block state, or {@code null} if the table does not describe it.
   *
   * <p>A {@code null} here is a real failure, not a default to paper over: it means the capture's
   * block data references a state the trait table was not generated for, so the two are out of step
   * and every block of that kind would otherwise be silently mispriced.
   *
   * @param blockState the block state string
   * @return the traits, or {@code null}
   */
  public @Nullable BlockTraits get(String blockState) {
    return traits.get(blockState);
  }

  /**
   * Returns the number of described block states.
   *
   * @return the table size
   */
  public int size() {
    return traits.size();
  }

  /**
   * Returns the mapping.
   *
   * @return an immutable map of block state to traits
   */
  public Map<String, BlockTraits> asMap() {
    return traits;
  }

  /**
   * Resolves traits for every entry of a palette, in palette-index order.
   *
   * <p>Done once when a column is decoded, so the hot path is an array index rather than a hash
   * lookup and a string comparison.
   *
   * @param palette the palette
   * @return traits indexed by palette index
   * @throws CaptureFormatException if any palette entry is missing from this table
   */
  public BlockTraits[] resolve(BlockPalette palette) throws CaptureFormatException {
    BlockTraits[] resolved = new BlockTraits[palette.size()];
    for (int i = 0; i < resolved.length; i++) {
      String state = palette.state(i);
      BlockTraits found = traits.get(state);
      if (found == null) {
        throw new CaptureFormatException(
            "the trait table does not describe '"
                + state
                + "'; it is stale against this capture's block data. Regenerate it with"
                + " /copier traits on a server of the captured version.");
      }
      resolved[i] = found;
    }
    return resolved;
  }

  /**
   * Writes the table.
   *
   * @param path the file
   * @return {@code true} if the file changed, {@code false} if it was already identical
   * @throws IOException if the file cannot be written
   */
  public boolean write(Path path) throws IOException {
    StringBuilder text = new StringBuilder(traits.size() * 64);
    text.append(HEADER).append('\n').append(COLUMNS).append('\n');
    // Sorted, so regenerating produces a reviewable diff rather than a reshuffle.
    for (Map.Entry<String, BlockTraits> entry : new TreeMap<>(traits).entrySet()) {
      BlockTraits value = entry.getValue();
      text.append(entry.getKey())
          .append('\t')
          .append("0x")
          .append(Long.toHexString(value.bits()))
          .append('\t')
          .append(value.breakTimeSeconds())
          .append('\t')
          .append(value.speedFactor())
          .append('\t')
          .append(value.damagePerSecond())
          .append('\n');
    }
    byte[] encoded = text.toString().getBytes(StandardCharsets.UTF_8);
    if (Files.exists(path) && java.util.Arrays.equals(Files.readAllBytes(path), encoded)) {
      return false;
    }
    Path parent = path.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Files.write(path, encoded);
    return true;
  }

  /**
   * Reads a table.
   *
   * @param path the file
   * @return the table
   * @throws IOException if the file cannot be read or parsed
   */
  public static TraitTable read(Path path) throws IOException {
    List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
    if (lines.isEmpty() || !lines.get(0).equals(HEADER)) {
      String found = lines.isEmpty() ? "an empty file" : "'" + lines.get(0) + "'";
      throw new CaptureFormatException(
          path
              + ": expected '"
              + HEADER
              + "' on the first line but found "
              + found
              + ". A table written against different trait bits parses cleanly and answers wrongly,"
              + " so it is refused rather than used. Regenerate it with:"
              + "  ./gradlew captureCorpus -PacceptMinecraftEula=true");
    }
    Map<String, BlockTraits> traits = new java.util.HashMap<>();
    for (int i = 1; i < lines.size(); i++) {
      String line = lines.get(i);
      if (line.isEmpty() || line.charAt(0) == '#') {
        continue;
      }
      String[] fields = line.split("\t", -1);
      if (fields.length != 5) {
        throw new CaptureFormatException(
            path + ":" + (i + 1) + ": expected 5 tab-separated fields, found " + fields.length);
      }
      try {
        BlockTraits value =
            new BlockTraits(
                Long.parseUnsignedLong(fields[1].substring(2), 16),
                Double.parseDouble(fields[2]),
                Double.parseDouble(fields[3]),
                Double.parseDouble(fields[4]));
        if (traits.putIfAbsent(fields[0], value) != null) {
          throw new CaptureFormatException(
              path + ":" + (i + 1) + ": '" + fields[0] + "' is described twice");
        }
      } catch (NumberFormatException | StringIndexOutOfBoundsException e) {
        throw new CaptureFormatException(path + ":" + (i + 1) + ": malformed number", e);
      }
    }
    return new TraitTable(Map.copyOf(traits));
  }

  /**
   * Creates a builder.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /** Accumulates traits, keeping the first description of any state. */
  public static final class Builder {

    private final Map<String, BlockTraits> traits = new java.util.HashMap<>();

    private Builder() {}

    /**
     * Records traits for a block state, if it is not already described.
     *
     * @param blockState the block state string
     * @param value the traits
     * @return this builder
     */
    public Builder add(String blockState, BlockTraits value) {
      traits.putIfAbsent(
          Objects.requireNonNull(blockState, "blockState"), Objects.requireNonNull(value, "value"));
      return this;
    }

    /**
     * Returns whether a state is already described.
     *
     * @param blockState the block state string
     * @return {@code true} if described
     */
    public boolean contains(String blockState) {
      return traits.containsKey(blockState);
    }

    /**
     * Returns the number of described states.
     *
     * @return the size
     */
    public int size() {
      return traits.size();
    }

    /**
     * Builds the table.
     *
     * @return the table
     */
    public TraitTable build() {
      return new TraitTable(Map.copyOf(traits));
    }
  }
}
