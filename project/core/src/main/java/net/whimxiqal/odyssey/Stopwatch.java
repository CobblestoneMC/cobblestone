/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey;

public class Stopwatch {

  private long duration;
  private long startedAt;

  public Stopwatch() {
    this.duration = 0;
    this.startedAt = 0;
  }

  public void resume() {
    if (this.startedAt == 0) {
      this.startedAt = System.currentTimeMillis();
    }
  }

  public void pause() {
    if (this.startedAt > 0) {
      this.duration += System.currentTimeMillis() - this.startedAt;
      this.startedAt = 0;
    }
  }

  public long elapsed() {
    if (this.startedAt == 0) {
      return this.duration;
    }
    return this.duration + (System.currentTimeMillis() - this.startedAt);
  }
}
