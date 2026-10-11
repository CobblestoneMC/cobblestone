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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The on-disk shape of a capture, and the only place that knows it.
 *
 * <pre>
 *   &lt;capture&gt;/
 *     capture.meta                       world list, Y bounds, provenance
 *     traits.tsv                         block state → traits
 *     &lt;namespace&gt;/&lt;path&gt;/c.&lt;x&gt;.&lt;z&gt;.sbc   one file per captured chunk column
 * </pre>
 *
 * <p>Scenarios are not in here. They belong to the corpus rather than to one capture — several
 * scenarios routinely share terrain — and keeping them out means a capture may be called anything,
 * including {@code scenarios}, without a collision. See {@link CorpusLayout}.
 *
 * <p><b>There is no manifest of what was captured.</b> Every column file states its own coordinates
 * and section mask, so the directory listing is the manifest — which means a capture cannot
 * disagree with its own index, and re-capturing part of a region never has to rewrite a shared file
 * that everything else depends on.
 *
 * <p><b>A world key becomes a directory path, not a mangled name.</b> {@code minecraft:the_nether}
 * is stored under {@code minecraft/the_nether}. A colon is not a legal path character on Windows,
 * so something has to give — but replacing it with a dot would be ambiguous, since a namespaced key
 * may legally contain dots in <em>both</em> halves, making {@code a.b:c} and {@code a:b.c} collide.
 * A path separator cannot appear in a namespace at all, so splitting there is lossless, and it
 * nests naturally for the slashes a key's path is also allowed to contain.
 */
public final class CaptureDirectory {

  /** The file holding a capture's provenance and world list. */
  public static final String META_FILE = "capture.meta";

  private static final String COLUMN_SUFFIX = ".sbc";

  private final Path root;

  private CaptureDirectory(Path root) {
    this.root = root;
  }

  /**
   * Returns a handle on the capture rooted at the given directory. The directory need not exist.
   *
   * @param root the capture directory
   * @return the handle
   */
  public static CaptureDirectory at(Path root) {
    return new CaptureDirectory(root);
  }

  /**
   * Returns the capture's root directory.
   *
   * @return the root
   */
  public Path root() {
    return root;
  }

  /**
   * Returns the path of the trait table.
   *
   * @return the trait table path
   */
  public Path traitTable() {
    return root.resolve(TraitTable.FILE_NAME);
  }

  /**
   * Returns the path of the metadata file.
   *
   * @return the metadata path
   */
  public Path meta() {
    return root.resolve(META_FILE);
  }

  /**
   * Returns the directory holding one world's columns.
   *
   * @param worldKey the namespaced world key
   * @return the world directory
   */
  public Path world(String worldKey) {
    int colon = worldKey.indexOf(':');
    String namespace = colon < 0 ? "minecraft" : worldKey.substring(0, colon);
    String path = colon < 0 ? worldKey : worldKey.substring(colon + 1);
    Path dir = root.resolve(namespace);
    for (String segment : path.split("/")) {
      dir = dir.resolve(segment);
    }
    return dir;
  }

  /**
   * Returns the path of one chunk column's file.
   *
   * @param worldKey the namespaced world key
   * @param chunkX the chunk X
   * @param chunkZ the chunk Z
   * @return the column file path
   */
  public Path column(String worldKey, int chunkX, int chunkZ) {
    return world(worldKey).resolve("c." + chunkX + "." + chunkZ + COLUMN_SUFFIX);
  }

  /**
   * Returns every column file present for a world, or an empty list if the world has none.
   *
   * @param worldKey the namespaced world key
   * @return the column file paths
   * @throws IOException if the directory cannot be listed
   */
  public List<Path> listColumns(String worldKey) throws IOException {
    Path dir = world(worldKey);
    if (!Files.isDirectory(dir)) {
      return List.of();
    }
    try (Stream<Path> entries = Files.list(dir)) {
      return entries
          .filter(p -> p.getFileName().toString().endsWith(COLUMN_SUFFIX))
          .sorted()
          .toList();
    }
  }

  /**
   * Counts the column files across every world in the capture.
   *
   * <p>Used by {@code /copier estimate} to report the running corpus total, since the file count is
   * a harder ceiling than the byte count: a version-controlled corpus stops being pleasant to work
   * with long before it stops fitting on disk.
   *
   * @return the number of column files
   * @throws IOException if the capture cannot be walked
   */
  public int countColumns() throws IOException {
    if (!Files.isDirectory(root)) {
      return 0;
    }
    try (Stream<Path> entries = Files.walk(root)) {
      return (int) entries.filter(p -> p.getFileName().toString().endsWith(COLUMN_SUFFIX)).count();
    }
  }

  /**
   * Writes the capture metadata, a sorted key-value file.
   *
   * <p>Sorted and free of timestamps of its own, for the same reason everything else here is: a
   * capture that is re-taken without changing must produce identical bytes. The capture time is a
   * recorded value rather than an incidental one, so re-capturing does move it — which is correct,
   * since it is provenance.
   *
   * @param values the metadata
   * @throws IOException if the file cannot be written
   */
  public void writeMeta(Map<String, String> values) throws IOException {
    StringBuilder text = new StringBuilder();
    text.append("#stonebrick-capture 1\n");
    new java.util.TreeMap<>(values)
        .forEach((key, value) -> text.append(key).append('\t').append(value).append('\n'));
    Files.createDirectories(root);
    Files.write(meta(), text.toString().getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Reads the capture metadata.
   *
   * @return the metadata
   * @throws IOException if the file cannot be read or parsed
   */
  public Map<String, String> readMeta() throws IOException {
    List<String> lines = Files.readAllLines(meta(), StandardCharsets.UTF_8);
    Map<String, String> values = new LinkedHashMap<>();
    for (String line : lines) {
      if (line.isEmpty() || line.charAt(0) == '#') {
        continue;
      }
      int tab = line.indexOf('\t');
      if (tab < 0) {
        throw new CaptureFormatException(meta() + ": line has no tab separator: " + line);
      }
      values.put(line.substring(0, tab), line.substring(tab + 1));
    }
    return values;
  }

  /**
   * Parses a column file name back into its chunk coordinates.
   *
   * @param fileName the file name, e.g. {@code c.12.-34.sbc}
   * @return {@code [chunkX, chunkZ]}
   * @throws CaptureFormatException if the name is not a column file name
   */
  public static int[] parseColumnName(String fileName) throws CaptureFormatException {
    if (!fileName.startsWith("c.") || !fileName.endsWith(COLUMN_SUFFIX)) {
      throw new CaptureFormatException("not a column file name: " + fileName);
    }
    String body = fileName.substring(2, fileName.length() - COLUMN_SUFFIX.length());
    // Coordinates may be negative, so split on the separator rather than on every dot.
    int split = body.indexOf('.', body.charAt(0) == '-' ? 1 : 0);
    if (split < 0) {
      throw new CaptureFormatException("not a column file name: " + fileName);
    }
    try {
      return new int[] {
        Integer.parseInt(body.substring(0, split)), Integer.parseInt(body.substring(split + 1))
      };
    } catch (NumberFormatException e) {
      throw new CaptureFormatException("not a column file name: " + fileName, e);
    }
  }

  /**
   * Returns the world keys this capture holds columns for.
   *
   * @return the world keys
   * @throws IOException if the capture cannot be listed
   */
  public List<String> worlds() throws IOException {
    if (!Files.isDirectory(root)) {
      return List.of();
    }
    // Found by looking for directories that actually hold columns rather than by assuming a depth:
    // a key's path may nest, so the shape of the tree is not known in advance.
    List<String> keys = new ArrayList<>();
    try (Stream<Path> entries = Files.walk(root)) {
      for (Path dir : entries.filter(Files::isDirectory).sorted().toList()) {
        if (dir.equals(root) || !holdsColumns(dir)) {
          continue;
        }
        Path relative = root.relativize(dir);
        StringBuilder key = new StringBuilder(relative.getName(0).toString()).append(':');
        for (int i = 1; i < relative.getNameCount(); i++) {
          if (i > 1) {
            key.append('/');
          }
          key.append(relative.getName(i));
        }
        keys.add(key.toString());
      }
    }
    keys.sort(null);
    return keys;
  }

  private static boolean holdsColumns(Path dir) throws IOException {
    try (Stream<Path> entries = Files.list(dir)) {
      return entries.anyMatch(path -> path.getFileName().toString().endsWith(COLUMN_SUFFIX));
    }
  }
}
