/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper.plugin;

import java.util.Map;
import net.whimxiqal.odyssey.paper.plugin.api.PaperNavigatorFactory;
import net.whimxiqal.odyssey.paper.plugin.api.PaperNavigatorService;

public class PaperTrailNavigatorService implements PaperNavigatorService {
  /** The navigator id, matched by {@code /navigate -navigator trail}. */
  public static final String KEY = "trail";

  private final PaperTrailNavigatorFactory factory;

  public PaperTrailNavigatorService(PaperTrailNavigatorFactory factory) {
    this.factory = factory;
  }

  @Override
  public Map<String, PaperNavigatorFactory> compute() {
    return Map.of(KEY, factory);
  }
}
