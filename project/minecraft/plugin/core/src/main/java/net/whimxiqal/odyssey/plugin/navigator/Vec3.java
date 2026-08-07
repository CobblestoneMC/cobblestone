/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.plugin.navigator;

/**
 * A minimal immutable 3D double vector for platform-neutral trail geometry. Platform navigators
 * convert their native locations to this so the follow math ({@link TrailProgress}) stays testable
 * without a server.
 *
 * @param x the x-coordinate
 * @param y the y-coordinate
 * @param z the z-coordinate
 */
public record Vec3(double x, double y, double z) {

  /**
   * Returns {@code this - other}.
   *
   * @param other the vector to subtract
   * @return the difference
   */
  public Vec3 minus(Vec3 other) {
    return new Vec3(x - other.x, y - other.y, z - other.z);
  }

  /**
   * Returns the dot product with {@code other}.
   *
   * @param other the other vector
   * @return the dot product
   */
  public double dot(Vec3 other) {
    return x * other.x + y * other.y + z * other.z;
  }

  /**
   * Returns the squared length (cheaper than the length; enough for comparisons and projection).
   *
   * @return the squared length
   */
  public double lengthSquared() {
    return x * x + y * y + z * z;
  }
}
