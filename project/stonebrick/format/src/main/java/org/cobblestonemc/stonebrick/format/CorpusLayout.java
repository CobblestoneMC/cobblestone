/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.format;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Where a corpus keeps its captures and its scenarios.
 *
 * <pre>
 *   &lt;corpus&gt;/
 *     captures/&lt;name&gt;/     terrain, written by /copier
 *     scenarios/*.yml      what to search across it
 * </pre>
 *
 * <p>Two directories, rather than captures sitting loose beside the scenario files. A capture may
 * then be named anything at all — including {@code scenarios} — and neither the copier nor the
 * runner has to know a reserved word. Reserving one would have patched the ambiguity; separating
 * them removes it.
 *
 * <p>The copier writes this same shape under its own output folder, so moving a finished capture
 * into a corpus is a directory copy with nothing to rename.
 */
public final class CorpusLayout {

  /** The directory holding captures. */
  public static final String CAPTURES = "captures";

  /** The directory holding scenario definitions. */
  public static final String SCENARIOS = "scenarios";

  private final Path root;

  private CorpusLayout(Path root) {
    this.root = root;
  }

  /**
   * Returns a handle on the corpus rooted at the given directory.
   *
   * @param root the corpus root
   * @return the layout
   */
  public static CorpusLayout at(Path root) {
    return new CorpusLayout(root);
  }

  /**
   * Returns the corpus root.
   *
   * @return the root
   */
  public Path root() {
    return root;
  }

  /**
   * Returns the directory scenario files live in.
   *
   * @return the scenarios directory
   */
  public Path scenarios() {
    return root.resolve(SCENARIOS);
  }

  /**
   * Returns the directory all captures live under.
   *
   * @return the captures directory
   */
  public Path captures() {
    return root.resolve(CAPTURES);
  }

  /**
   * Returns a handle on one capture.
   *
   * @param name the capture name
   * @return the capture directory
   */
  public CaptureDirectory capture(String name) {
    return CaptureDirectory.at(captures().resolve(name));
  }

  /**
   * Returns the names of the captures present.
   *
   * @return the capture names, sorted
   * @throws IOException if the directory cannot be listed
   */
  public List<String> captureNames() throws IOException {
    if (!Files.isDirectory(captures())) {
      return List.of();
    }
    try (Stream<Path> entries = Files.list(captures())) {
      List<String> names = new ArrayList<>();
      entries.filter(Files::isDirectory).forEach(path -> names.add(path.getFileName().toString()));
      names.sort(null);
      return names;
    }
  }
}
