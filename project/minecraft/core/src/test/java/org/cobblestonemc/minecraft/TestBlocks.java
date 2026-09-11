/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft;

/** Factory helpers producing {@link MinecraftBlock}s for mode tests. */
public final class TestBlocks {

  private TestBlocks() {}

  static MinecraftBlock air() {
    return new MinecraftBlock() {
      @Override
      public boolean isPassable() {
        return true;
      }

      @Override
      public boolean isSolidTop() {
        return false;
      }
    };
  }

  public static MinecraftBlock solid() {
    return solid(1.0, 1.0);
  }

  public static MinecraftBlock solid(double breakTime) {
    return solid(breakTime, 1.0);
  }

  /** A full solid block with a given break time and top-surface speed factor. */
  static MinecraftBlock solid(double breakTime, double speedFactor) {
    return new MinecraftBlock() {
      @Override
      public boolean isPassable() {
        return false;
      }

      @Override
      public boolean isSolidTop() {
        return true;
      }

      @Override
      public double breakTimeSeconds() {
        return breakTime;
      }

      @Override
      public double speedFactor() {
        return speedFactor;
      }
    };
  }

  public static MinecraftBlock bedrock() {
    return solid(Double.POSITIVE_INFINITY, 1.0);
  }

  public static MinecraftBlock ice() {
    return solid(1.0, 2.0);
  }

  public static MinecraftBlock soulSand() {
    return solid(1.0, 0.4);
  }

  public static MinecraftBlock slab() {
    return new MinecraftBlock() {
      @Override
      public boolean isPassable() {
        return false;
      }

      @Override
      public boolean isSolidTop() {
        return true;
      }

      @Override
      public boolean isHalfHeight() {
        return true;
      }

      @Override
      public double breakTimeSeconds() {
        return 1.0;
      }
    };
  }

  public static MinecraftBlock water() {
    return new MinecraftBlock() {
      @Override
      public boolean isPassable() {
        return false;
      }

      @Override
      public boolean isSolidTop() {
        return false;
      }

      @Override
      public boolean isWater() {
        return true;
      }
    };
  }

  public static MinecraftBlock pressurePlate() {
    return new MinecraftBlock() {
      @Override
      public boolean isPassable() {
        return true;
      }

      @Override
      public boolean isSolidTop() {
        return true;
      }

      @Override
      public boolean isPressurePlate() {
        return true;
      }
    };
  }

  public static MinecraftBlock closedDoor(boolean opensByHand) {
    return new MinecraftBlock() {
      @Override
      public boolean isPassable() {
        return false;
      }

      @Override
      public boolean isSolidTop() {
        return false;
      }

      @Override
      public boolean isDoor() {
        return true;
      }

      @Override
      public boolean opensByHand() {
        return opensByHand;
      }
    };
  }

  /** A trapdoor standing open: vertical, and so a wall across the face it is hung on. */
  public static MinecraftBlock openTrapdoor() {
    return new MinecraftBlock() {
      @Override
      public boolean isPassable() {
        return false;
      }

      @Override
      public boolean isSolidTop() {
        return false;
      }

      @Override
      public boolean isDoor() {
        return true;
      }

      @Override
      public boolean isTrapdoor() {
        return true;
      }

      @Override
      public boolean isOpen() {
        return true;
      }

      @Override
      public boolean opensByHand() {
        return true;
      }
    };
  }

  /**
   * A door standing open. Still impassable — passability is a material-level fact, so a door reads
   * the same whichever way it is standing (see {@code MinecraftBlock}); only {@code DoorMode} can
   * cross one.
   */
  public static MinecraftBlock openDoor() {
    return new MinecraftBlock() {
      @Override
      public boolean isPassable() {
        return false;
      }

      @Override
      public boolean isSolidTop() {
        return false;
      }

      @Override
      public boolean isDoor() {
        return true;
      }

      @Override
      public boolean isOpen() {
        return true;
      }

      @Override
      public boolean opensByHand() {
        return true;
      }
    };
  }
}
