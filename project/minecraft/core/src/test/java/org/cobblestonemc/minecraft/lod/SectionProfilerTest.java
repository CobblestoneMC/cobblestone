/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.lod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cobblestonemc.minecraft.MinecraftBlock;
import org.cobblestonemc.minecraft.MinecraftChunk;
import org.cobblestonemc.minecraft.lod.SectionProfile.Axis;
import org.junit.jupiter.api.Test;

class SectionProfilerTest {

  /** A block that answers whatever the test needs and nothing more. */
  private record TestBlock(
      boolean passable, boolean solidTop, boolean water, boolean climbable, double breakTime)
      implements MinecraftBlock {

    @Override
    public boolean isPassable() {
      return passable;
    }

    @Override
    public boolean isSolidTop() {
      return solidTop;
    }

    @Override
    public boolean isWater() {
      return water;
    }

    @Override
    public boolean supportsBoat() {
      return water;
    }

    @Override
    public boolean isClimbable() {
      return climbable;
    }

    @Override
    public double breakTimeSeconds() {
      return breakTime;
    }
  }

  private static final MinecraftBlock AIR = new TestBlock(true, false, false, false, 0.0);
  private static final MinecraftBlock STONE = new TestBlock(false, true, false, false, 1.5);
  private static final MinecraftBlock WATER = new TestBlock(false, false, true, false, 1.0 / 0.0);
  private static final MinecraftBlock BEDROCK = new TestBlock(false, true, false, false, 1.0 / 0.0);
  private static final MinecraftBlock LADDER = new TestBlock(true, false, false, true, 0.4);

  /** Builds a chunk from a function over section-local coordinates. */
  private interface Shape {
    MinecraftBlock at(int x, int y, int z);
  }

  private static MinecraftChunk chunk(Shape shape) {
    return (x, y, z) -> shape.at(x, Math.floorMod(y, 16), z);
  }

  @Test
  void flatGroundWalksAcrossBothLateralAxesButNotUpwards() {
    // Stone floor at y<=3, air above: a body can cross the section in X and Z, and cannot walk up.
    SectionProfile profile =
        new SectionProfiler().profile(chunk((x, y, z) -> y <= 3 ? STONE : AIR), 0);

    assertEquals(1, profile.components().size());
    SectionProfile.Component open = profile.components().get(0);
    assertEquals(1.0, open.coverage(Axis.X, Medium.WALK));
    assertEquals(1.0, open.coverage(Axis.Z, Medium.WALK));
    // Only the one slice just above the floor is standable, out of sixteen.
    assertEquals(1 / 16.0, open.coverage(Axis.Y, Medium.WALK));
    // Flying fills the whole air space.
    assertEquals(1.0, open.coverage(Axis.X, Medium.FLY));
    assertEquals(12 / 16.0, open.coverage(Axis.Y, Medium.FLY));
  }

  @Test
  void aWallDownTheMiddleSplitsTheSection() {
    // This is what components are for: without them, a profile would report a crossing that the
    // wall makes impossible.
    SectionProfile profile =
        new SectionProfiler().profile(chunk((x, y, z) -> x == 8 ? STONE : AIR), 0);

    assertEquals(2, profile.components().size());
    for (SectionProfile.Component side : profile.components()) {
      // Neither half spans the full width; the wall is at slice 8.
      assertTrue(side.coverage(Axis.X, Medium.FLY) < 1.0, "a half should not span X");
      assertEquals(1.0, side.coverage(Axis.Z, Medium.FLY), "but each half spans Z");
    }
  }

  @Test
  void aLakeSurfaceCarriesABoatSidewaysAndNotUpwards() {
    // The anisotropy that falls out rather than being written as a rule: a navigable surface is one
    // slice tall, so boats get no vertical coverage without anyone saying boats do not go up.
    SectionProfile profile =
        new SectionProfiler().profile(chunk((x, y, z) -> y <= 7 ? WATER : AIR), 0);

    SectionProfile.Component open = profile.components().get(0);
    assertEquals(1.0, open.coverage(Axis.X, Medium.BOAT));
    assertEquals(1.0, open.coverage(Axis.Z, Medium.BOAT));
    assertEquals(1 / 16.0, open.coverage(Axis.Y, Medium.BOAT));
  }

  @Test
  void waterBelowTheSurfaceIsSwimmableButNotNavigable() {
    SectionProfile profile =
        new SectionProfiler().profile(chunk((x, y, z) -> y <= 7 ? WATER : AIR), 0);
    SectionProfile.Component open = profile.components().get(0);

    // Swimming fills the water column; only its top slice floats a boat.
    assertTrue(
        open.coverage(Axis.Y, Medium.SWIM) > open.coverage(Axis.Y, Medium.BOAT),
        "swim should reach deeper than boat");
  }

  @Test
  void aLadderShaftClimbsVertically() {
    SectionProfile profile =
        new SectionProfiler()
            .profile(chunk((x, y, z) -> x == 8 && z == 8 ? LADDER : x < 7 ? AIR : STONE), 0);

    SectionProfile.Component shaft =
        profile.components().stream()
            .filter(c -> c.coverage(Axis.Y, Medium.CLIMB) > 0)
            .findFirst()
            .orElseThrow();
    assertEquals(1.0, shaft.coverage(Axis.Y, Medium.CLIMB));
  }

  @Test
  void solidRockIsStillProfiledAsMineable() {
    // A section with no open space must not vanish: digging through it is a route Cobblestone
    // offers, and a coarse layer that reported nothing here would never consider it.
    SectionProfile profile = new SectionProfiler().profile(chunk((x, y, z) -> STONE), 0);

    assertTrue(profile.solid());
    assertEquals(1, profile.components().size());
    SectionProfile.Component rock = profile.components().get(0);
    assertEquals(0, rock.openVolume());
    assertEquals(1.0, rock.coverage(Axis.X, Medium.MINE));
    assertEquals(1.0, rock.coverage(Axis.Y, Medium.MINE));
    assertEquals(1.5, rock.averageBreakTime());
    assertEquals(0.0, rock.coverage(Axis.X, Medium.WALK));
  }

  @Test
  void bedrockCannotBeMinedThrough() {
    SectionProfile profile = new SectionProfiler().profile(chunk((x, y, z) -> BEDROCK), 0);

    assertEquals(0.0, profile.components().get(0).coverage(Axis.X, Medium.MINE));
  }

  @Test
  void tooManyComponentsAreMergedAndSaidToBe() {
    // A lattice of sealed one-block pockets: far more components than the cap.
    SectionProfile profile =
        new SectionProfiler()
            .profile(chunk((x, y, z) -> x % 2 == 1 && y % 2 == 1 && z % 2 == 1 ? AIR : STONE), 0);

    assertTrue(profile.merged(), "the profile must admit it lost detail");
    assertEquals(SectionProfile.MAX_COMPONENTS, profile.components().size());
  }

  @Test
  void componentsComeBackLargestFirst() {
    // A thin slab on one side of a wall, everything else open.
    SectionProfile profile =
        new SectionProfiler().profile(chunk((x, y, z) -> x == 2 ? STONE : x < 2 ? AIR : AIR), 0);

    assertEquals(2, profile.components().size());
    assertTrue(
        profile.components().get(0).openVolume() >= profile.components().get(1).openVolume(),
        "largest first, so a consumer reading only the first gets the one that matters");
  }

  @Test
  void theCheapestAvailableMediumIsWhatAnAgentWouldUse() {
    SectionProfile profile =
        new SectionProfiler().profile(chunk((x, y, z) -> y <= 7 ? WATER : AIR), 0);
    SectionProfile.Component open = profile.components().get(0);

    // A walker with a boat takes the boat; without one, swimming is all that is left laterally.
    assertSame(Medium.BOAT, open.cheapest(Axis.X, java.util.List.of(Medium.WALK, Medium.BOAT)));
    assertSame(Medium.SWIM, open.cheapest(Axis.X, java.util.List.of(Medium.WALK, Medium.SWIM)));
    // And a flier beats both, everywhere.
    assertSame(
        Medium.FLY, open.cheapest(Axis.X, java.util.List.of(Medium.WALK, Medium.BOAT, Medium.FLY)));
  }

  @Test
  void profilingIsReusableWithoutLeakingBetweenSections() {
    // One profiler, two different sections: the scratch arrays must not carry state across.
    SectionProfiler profiler = new SectionProfiler();
    SectionProfile solid = profiler.profile(chunk((x, y, z) -> STONE), 0);
    SectionProfile open = profiler.profile(chunk((x, y, z) -> AIR), 0);
    SectionProfile solidAgain = profiler.profile(chunk((x, y, z) -> STONE), 0);

    assertTrue(solid.solid());
    assertFalse(open.solid());
    assertTrue(solidAgain.solid());
    assertEquals(1.0, open.components().get(0).coverage(Axis.Y, Medium.FLY));
  }
}
