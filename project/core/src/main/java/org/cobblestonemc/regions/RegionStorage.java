/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.regions;

import java.io.IOException;
import java.util.Collection;
import org.jetbrains.annotations.Nullable;

/**
 * Where a {@link RegionStore} keeps its sections beyond memory: {@link RegionFiles} on disk, or
 * {@link #NONE}, nowhere.
 *
 * <p>A store calls it from its one disk thread only.
 */
public sealed interface RegionStorage permits RegionFiles, RegionStorage.None {

  /** Keeps nothing: every section a store forgets is built again. */
  RegionStorage NONE = new None();

  /**
   * Returns whether sections written here can be read back. A store that keeps nothing does not
   * track what it would have to write.
   *
   * @return {@code true} if they can
   */
  boolean keeps();

  /**
   * Reads a section, or returns {@code null} if none is kept or the kept one is unreadable.
   *
   * @param sectionX the section X
   * @param sectionY the section Y
   * @param sectionZ the section Z
   * @return the section, or {@code null}
   * @throws IOException if the storage cannot be read at all
   */
  @Nullable
  RegionSection read(int sectionX, int sectionY, int sectionZ) throws IOException;

  /**
   * Writes sections and removes others.
   *
   * @param sections the sections to write
   * @param removed the keys of sections to remove
   * @throws IOException if the write fails
   */
  void write(Collection<RegionSection> sections, Collection<Long> removed) throws IOException;

  /**
   * Deletes everything kept.
   *
   * @throws IOException if something cannot be deleted
   */
  void delete() throws IOException;

  /**
   * Releases whatever is held open.
   *
   * @throws IOException if something cannot be released
   */
  void close() throws IOException;

  /**
   * Returns the bytes of sections read so far.
   *
   * @return the bytes
   */
  long bytesRead();

  /**
   * Returns the bytes written so far.
   *
   * @return the bytes
   */
  long bytesWritten();

  /** {@link #NONE}. */
  final class None implements RegionStorage {

    private None() {}

    @Override
    public boolean keeps() {
      return false;
    }

    @Override
    public @Nullable RegionSection read(int sectionX, int sectionY, int sectionZ) {
      return null;
    }

    @Override
    public void write(Collection<RegionSection> sections, Collection<Long> removed) {}

    @Override
    public void delete() {}

    @Override
    public void close() {}

    @Override
    public long bytesRead() {
      return 0;
    }

    @Override
    public long bytesWritten() {
      return 0;
    }
  }
}
