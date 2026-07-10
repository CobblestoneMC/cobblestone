/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.minecraft;

import net.whimxiqal.odyssey.minecraft.api.MinecraftBlock;

/** Factory helpers producing {@link MinecraftBlock}s for mode tests. */
final class TestBlocks {

  private TestBlocks() {
  }

  static MinecraftBlock air() {
    return new MinecraftBlock() {
      @Override
      public String typeKey() {
        return "minecraft:air";
      }

      @Override
      public boolean isPassable() {
        return true;
      }

      @Override
      public boolean isSolidTop() {
        return false;
      }

      @Override
      public boolean isWater() {
        return false;
      }
    };
  }

  static MinecraftBlock solid() {
    return solid(1.0, 1.0);
  }

  static MinecraftBlock solid(double breakTime) {
    return solid(breakTime, 1.0);
  }

  /** A full solid block with a given break time and top-surface speed factor. */
  static MinecraftBlock solid(double breakTime, double speedFactor) {
    return new MinecraftBlock() {
      @Override
      public String typeKey() {
        return "minecraft:stone";
      }

      @Override
      public boolean isPassable() {
        return false;
      }

      @Override
      public boolean isSolidTop() {
        return true;
      }

      @Override
      public boolean isWater() {
        return false;
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

  static MinecraftBlock bedrock() {
    return solid(Double.POSITIVE_INFINITY, 1.0);
  }

  static MinecraftBlock ice() {
    return solid(1.0, 2.0);
  }

  static MinecraftBlock soulSand() {
    return solid(1.0, 0.4);
  }

  static MinecraftBlock slab() {
    return new MinecraftBlock() {
      @Override
      public String typeKey() {
        return "minecraft:stone_slab";
      }

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
      public boolean isWater() {
        return false;
      }

      @Override
      public double breakTimeSeconds() {
        return 1.0;
      }
    };
  }

  static MinecraftBlock water() {
    return new MinecraftBlock() {
      @Override
      public String typeKey() {
        return "minecraft:water";
      }

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

  static MinecraftBlock pressurePlate() {
    return new MinecraftBlock() {
      @Override
      public String typeKey() {
        return "minecraft:stone_pressure_plate";
      }

      @Override
      public boolean isPassable() {
        return true;
      }

      @Override
      public boolean isSolidTop() {
        return true;
      }

      @Override
      public boolean isWater() {
        return false;
      }

      @Override
      public boolean isPressurePlate() {
        return true;
      }
    };
  }

  static MinecraftBlock closedDoor(boolean opensByHand) {
    return new MinecraftBlock() {
      @Override
      public String typeKey() {
        return opensByHand ? "minecraft:oak_door" : "minecraft:iron_door";
      }

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
        return false;
      }

      @Override
      public boolean isDoor() {
        return true;
      }

      @Override
      public boolean isOpen() {
        return false;
      }

      @Override
      public boolean opensByHand() {
        return opensByHand;
      }
    };
  }
}
