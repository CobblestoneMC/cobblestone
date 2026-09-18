/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.cobblestonemc.CobblestoneLogger;

/**
 * Decides whether the server internals {@link NMSChunk} reads are actually present, and rations the
 * complaints when they are not.
 *
 * <p>Cobblestone compiles against one Paper dev bundle but runs on whatever the admin installed.
 * The public Bukkit API it uses elsewhere is stable across versions; the internals here are not, so
 * on a server newer (or older) than the bundle a class can be gone, a method renamed, a signature
 * changed. Left alone that surfaces as a {@link LinkageError} <em>per chunk read</em> — a search
 * asks for thousands — which is how you turn a version mismatch into a console no one can read and
 * a log no one can ship.
 *
 * <p>So two things happen here. {@link #available} probes the symbols once, reflectively, before
 * anything loads {@link NMSChunk}: this class deliberately names no server type directly, so it
 * cannot itself fail to link, and a failed probe means the NMS path is never entered at all rather
 * than entered and caught. And if something slips through anyway — a symbol that resolves but
 * misbehaves — {@link #reportLinkageFailure} disables the path for the rest of the session on the
 * first {@code LinkageError}, so the flood is bounded at one.
 *
 * <p>Ordinary read failures (a corrupt region file, a disk error) are not a version problem and do
 * not disable anything, but they can still repeat per chunk, so {@link #reportReadFailure}
 * collapses them to one message per {@link #FAILURE_LOG_INTERVAL_MILLIS} with a count of what it
 * swallowed.
 *
 * <p>Disabling is not a loss of function: {@link PaperPlatformApi} falls back to loading the chunk
 * through Bukkit, which is what it did before any of this existed. The search still answers
 * correctly; it just costs the server what it used to cost.
 */
final class NMSSupport {

  /** The shortest gap between two read-failure messages; the rest are counted and folded in. */
  private static final long FAILURE_LOG_INTERVAL_MILLIS = 60_000L;

  /**
   * The internals {@link NMSChunk} needs, as {@code class name} to {@code member name}.
   *
   * <p>Members are matched by name only, not signature: a rename or removal is what version drift
   * actually looks like, and checking argument types here would mean naming the very classes this
   * probe exists to avoid naming.
   */
  private static final String[][] REQUIRED_MEMBERS = {
    {"ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO", "loadDataAsync"},
    {"ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO$RegionFileType", null},
    {"ca.spottedleaf.concurrentutil.util.Priority", null},
    {"ca.spottedleaf.concurrentutil.executor.Cancellable", "cancel"},
    {"org.bukkit.craftbukkit.CraftWorld", "getHandle"},
    {"net.minecraft.server.level.ServerChunkCache", "chunkMap"},
    {"net.minecraft.server.level.ChunkMap", "upgradeChunkTag"},
    {"net.minecraft.world.level.Level", "palettedContainerFactory"},
    {"net.minecraft.world.level.chunk.PalettedContainerFactory", "blockStatesContainerCodec"},
    {"net.minecraft.world.level.chunk.PalettedContainer", "get"},
    {"net.minecraft.world.level.chunk.status.ChunkStatus", "FULL"},
    {"net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase", "asBlockData"},
    {"net.minecraft.nbt.CompoundTag", "getListOrEmpty"},
  };

  private static final AtomicBoolean PROBED = new AtomicBoolean();
  private static final AtomicLong LAST_FAILURE_LOGGED_AT = new AtomicLong();
  private static final AtomicLong SUPPRESSED_FAILURES = new AtomicLong();

  private static volatile boolean usable = true;

  private NMSSupport() {}

  /**
   * Returns whether the offline-chunk path can be used, probing the server on the first call.
   *
   * @param logger the logger to tell the admin on, once, if it cannot
   * @return {@code true} if every internal {@link NMSChunk} needs is present
   */
  static boolean available(CobblestoneLogger logger) {
    if (PROBED.compareAndSet(false, true)) {
      String missing = probe();
      if (missing != null) {
        usable = false;
        reportUnsupported(logger, missing);
      }
    }
    return usable;
  }

  /**
   * Records that a server internal failed to link at runtime, disabling the path for good.
   *
   * <p>Called for {@link LinkageError} escaping a read. One is proof the probe was too optimistic
   * about this server, and every later read would fail the same way, so the path closes here rather
   * than failing thousands more times.
   *
   * @param logger the logger to tell the admin on
   * @param error the error that escaped
   */
  static void reportLinkageFailure(CobblestoneLogger logger, Throwable error) {
    boolean firstTime = usable;
    usable = false;
    if (firstTime) {
      reportUnsupported(logger, error.toString());
    }
  }

  /**
   * Logs a read failure that says nothing about the server version, at most once per {@link
   * #FAILURE_LOG_INTERVAL_MILLIS}.
   *
   * @param logger the logger
   * @param message the message, with {@code {}} placeholders
   * @param error the failure
   * @param args the placeholder arguments
   */
  static void reportReadFailure(
      CobblestoneLogger logger, String message, Throwable error, Object... args) {
    long now = System.currentTimeMillis();
    long last = LAST_FAILURE_LOGGED_AT.get();
    if (now - last < FAILURE_LOG_INTERVAL_MILLIS
        || !LAST_FAILURE_LOGGED_AT.compareAndSet(last, now)) {
      SUPPRESSED_FAILURES.incrementAndGet();
      return;
    }
    long suppressed = SUPPRESSED_FAILURES.getAndSet(0);
    if (suppressed > 0) {
      logger.error(
          message + " ({} further chunk read failures since the last message)",
          error,
          appended(args, suppressed));
    } else {
      logger.error(message, error, args);
    }
  }

  /**
   * Returns the first missing symbol as {@code Class#member}, or {@code null} if all are present.
   */
  private static String probe() {
    for (String[] required : REQUIRED_MEMBERS) {
      String className = required[0];
      String member = required[1];
      Class<?> type;
      try {
        type = Class.forName(className, false, NMSSupport.class.getClassLoader());
      } catch (ClassNotFoundException | LinkageError error) {
        return className;
      }
      if (member != null && !hasMember(type, member)) {
        return className + "#" + member;
      }
    }
    return null;
  }

  /** Whether a class declares a method or field of this name, anywhere in its hierarchy. */
  private static boolean hasMember(Class<?> type, String name) {
    try {
      for (Class<?> current = type; current != null; current = current.getSuperclass()) {
        for (java.lang.reflect.Method method : current.getDeclaredMethods()) {
          if (method.getName().equals(name)) {
            return true;
          }
        }
        for (java.lang.reflect.Field field : current.getDeclaredFields()) {
          if (field.getName().equals(name)) {
            return true;
          }
        }
      }
    } catch (LinkageError error) {
      return false; // the class is there but its own dependencies are not
    }
    return false;
  }

  private static void reportUnsupported(CobblestoneLogger logger, String detail) {
    logger.warn(
        "Cobblestone cannot read unloaded chunks directly from disk on this server: {} is missing"
            + " or has changed. This almost always means the server version is newer or older than"
            + " the one Cobblestone was built against — updating Cobblestone usually fixes it."
            + " Searches will keep working; chunks that are not loaded will be loaded through the"
            + " server instead, which is slower and heavier. This message is not repeated.",
        detail);
  }

  private static Object[] appended(Object[] args, Object extra) {
    Object[] combined = new Object[args.length + 1];
    System.arraycopy(args, 0, combined, 0, args.length);
    combined[args.length] = extra;
    return combined;
  }
}
