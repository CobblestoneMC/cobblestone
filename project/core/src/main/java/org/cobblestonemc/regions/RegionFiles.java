/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.regions;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import org.jetbrains.annotations.Nullable;

/**
 * Built sections on disk, for one domain: what lets a server start a search on terrain it built
 * before it restarted.
 *
 * <p><b>16×16×16 sections to a file, in sectors.</b> A file is a header with a slot per section,
 * then records in whole sectors of {@value #SECTOR} bytes:
 *
 * <pre>
 *   int    magic "CRG3"
 *   int    format version        layout of this file and of a section record
 *   int    rules version         the fill's and the terrain's rules the sections were built by
 *   int    classes               the terrain's class names, in order, hashed: what a stored class means
 *   double detour ratio          a fill setting that changes the regions
 *   int    section shift
 *   4096 x { int first sector, int length in bytes }   0, 0 for no section
 *   ... sectors: each record is { int length, int CRC32, deflated section }, padded to a sector
 * </pre>
 *
 * <p><b>A whole section is its slot.</b> Most sections are one class edge to edge — open sky, solid
 * rock — with nothing inside them to join, and all there is to say of one is its class. Such a
 * section is kept in its slot alone, {@code 0, -1} for none at all and {@code 0, -2 - class} for
 * one region, with no record and no sector: reading one back costs no read.
 *
 * <p><b>A write touches only its own record and slot.</b> A section that still fits its sectors is
 * written over them; one that has grown takes the first free run big enough, or the end of the
 * file, and gives its old sectors back; a removed section only clears its slot. One changed section
 * costs a sector or two, not the file.
 *
 * <p><b>A torn write reads as missing,</b> never as wrong: each record carries its length and a
 * checksum, and a record that does not match them is a section to build again. The files are a
 * cache, so that is all a crash can cost.
 *
 * <p><b>A file whose header does not match is empty.</b> Sections built under different rules, or
 * whose classes meant something else, are a different graph; they are ignored on read, and the file
 * is started afresh on the next write, never mixed in.
 *
 * <p>Thread-safe by being synchronized. {@link RegionStore} calls it from one disk thread, so the
 * lock is never contended; it is there so that nothing else has to know that.
 */
public final class RegionFiles {

  private static final int MAGIC = 0x43524733; // "CRG3"

  /** The layout of a file and of a section record. Bump it with any change to either. */
  static final int FORMAT_VERSION = 5;

  /** Sections a file holds along each axis. */
  static final int SPAN = 16;

  /** Bytes in a sector, the unit a record is allocated in. */
  static final int SECTOR = 1024;

  private static final int SLOTS = SPAN * SPAN * SPAN;

  /** Bytes before the slots: magic, two versions, the classes, the detour and the shift. */
  private static final int PREAMBLE = 4 + 4 + 4 + 4 + 8 + 4;

  /** Sectors the header takes, slots included. */
  static final int HEADER_SECTORS = (PREAMBLE + SLOTS * 8 + SECTOR - 1) / SECTOR;

  /** Files kept open: a search reads the sections of a few files one after another. */
  private static final int OPEN_FILES = 8;

  private final Path directory;
  private final RegionSpec<?, ?> spec;
  private final int rules;
  private final int classes;

  /** Open files, most recently used last. */
  private final LinkedHashMap<Long, Open> open = new LinkedHashMap<>(16, 0.75f, true);

  private long recordsRead;
  private long bytesRead;
  private long bytesWritten;

  /** Reused for every record: one is costly to make, and every call here holds the lock. */
  private final Deflater deflater = new Deflater(Deflater.BEST_SPEED);

  private final Inflater inflater = new Inflater();

  /**
   * Creates the store for one domain.
   *
   * @param directory where this domain's files go; created on the first write
   * @param spec how the domain's terrain is cut, recorded so a change of it is not read back
   */
  public RegionFiles(Path directory, RegionSpec<?, ?> spec) {
    this.directory = directory;
    this.spec = spec;
    // The fill's rules and the terrain's, in one number: a change to either is a different graph.
    this.rules = (RegionSectionBuilder.FILL_VERSION << 16) | spec.terrain().version();
    this.classes = classes(spec);
  }

  /**
   * The terrain's class names in order, hashed: a section stores its classes by ordinal, so
   * reordering, adding or retiring one changes what every stored number means.
   */
  private static int classes(RegionSpec<?, ?> spec) {
    StringBuilder names = new StringBuilder();
    for (Enum<?> clazz : spec.terrain().classes().getEnumConstants()) {
      names.append(clazz.name()).append(',');
    }
    return names.toString().hashCode();
  }

  /**
   * Returns a directory name for a domain key, safe on any file system.
   *
   * @param domainKey the domain's key, such as a namespaced domain key
   * @return the directory name
   */
  public static String directoryName(String domainKey) {
    return domainKey.replaceAll("[^A-Za-z0-9._-]", "_");
  }

  /**
   * Reads a section, or returns {@code null} if none is stored or the stored one is unreadable.
   *
   * @param sectionX the section X
   * @param sectionY the section Y
   * @param sectionZ the section Z
   * @return the section, or {@code null}
   * @throws IOException if the file cannot be read at all
   */
  public synchronized @Nullable RegionSection read(int sectionX, int sectionY, int sectionZ)
      throws IOException {
    Open file = open(fileKey(sectionX, sectionY, sectionZ), false);
    if (file == null) {
      return null;
    }
    int slot = slot(sectionX, sectionY, sectionZ);
    int first = file.first[slot];
    int length = file.length[slot];
    if (first == 0 && length < 0) {
      return RegionSection.whole(spec, sectionX, sectionY, sectionZ, length);
    }
    if (first == 0 || length <= 8 || (long) first * SECTOR + length > file.handle.length()) {
      return null;
    }
    byte[] record = new byte[length];
    file.handle.seek((long) first * SECTOR);
    file.handle.readFully(record);
    recordsRead++;
    bytesRead += length;
    DataInputStream in = new DataInputStream(new ByteArrayInputStream(record));
    int stored = in.readInt();
    int checksum = in.readInt();
    CRC32 crc = new CRC32();
    crc.update(record, 8, length - 8);
    if (stored != length - 8 || (int) crc.getValue() != checksum) {
      return null; // torn by a crash: build it again
    }
    try {
      RegionSection section = decode(record);
      if (section.sectionX != sectionX
          || section.sectionY != sectionY
          || section.sectionZ != sectionZ) {
        return null; // filed under the wrong section: treat it as missing and rebuild
      }
      return section;
    } catch (IOException malformed) {
      return null;
    }
  }

  /**
   * Writes sections and removes others, each in place in its file.
   *
   * @param sections the sections to store, replacing any stored before
   * @param removed the sections to remove, by {@link RegionSection#key}
   * @throws IOException if a file cannot be written; sections already written stay written
   */
  public synchronized void write(Collection<RegionSection> sections, Collection<Long> removed)
      throws IOException {
    for (long key : removed) {
      int sx = RegionSection.keyX(key);
      int sy = RegionSection.keyY(key);
      int sz = RegionSection.keyZ(key);
      Open file = open(fileKey(sx, sy, sz), false);
      if (file != null) {
        file.clear(slot(sx, sy, sz));
      }
    }
    for (RegionSection section : sections) {
      int sx = section.sectionX;
      int sy = section.sectionY;
      int sz = section.sectionZ;
      Open file = open(fileKey(sx, sy, sz), true);
      int whole = section.wholeCode();
      bytesWritten +=
          whole < 0
              ? file.whole(slot(sx, sy, sz), whole)
              : file.put(slot(sx, sy, sz), encode(section));
    }
  }

  /**
   * Returns how many section records have been read.
   *
   * @return the record count
   */
  public synchronized long recordsRead() {
    return recordsRead;
  }

  /**
   * Returns how many bytes of records have been read.
   *
   * @return the byte count
   */
  public synchronized long bytesRead() {
    return bytesRead;
  }

  /**
   * Returns how many bytes have been written, records and slots.
   *
   * @return the byte count
   */
  public synchronized long bytesWritten() {
    return bytesWritten;
  }

  /**
   * Closes every file held open.
   *
   * @throws IOException if one cannot be closed
   */
  public synchronized void close() throws IOException {
    for (Open file : open.values()) {
      file.handle.close();
    }
    open.clear();
  }

  /**
   * Opens a file, reading its slots, or returns {@code null} if it does not exist and {@code
   * create} is not set. A file whose header does not match is read as empty, and started afresh
   * once something is written to it.
   */
  private @Nullable Open open(long file, boolean create) throws IOException {
    Open known = open.get(file);
    if (known != null) {
      if (create && !known.matches) {
        known.reset();
      }
      return known;
    }
    Path path = path(file);
    if (!create && !Files.exists(path)) {
      return null;
    }
    Files.createDirectories(directory);
    Open opened = new Open(new RandomAccessFile(path.toFile(), "rw"));
    if (create && !opened.matches) {
      opened.reset();
    }
    open.put(file, opened);
    if (open.size() > OPEN_FILES) {
      Iterator<Map.Entry<Long, Open>> eldest = open.entrySet().iterator();
      Open closing = eldest.next().getValue();
      eldest.remove();
      closing.handle.close();
    }
    return opened;
  }

  private byte[] encode(RegionSection section) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream(1024);
    // Room for the length and checksum, filled in once the body is known.
    bytes.write(new byte[8]);
    deflater.reset();
    // Buffered: a record is written a byte or a short at a time, and the deflater, unbuffered,
    // would be called for each.
    try (DataOutputStream out =
        new DataOutputStream(
            new BufferedOutputStream(new DeflaterOutputStream(bytes, deflater, 4096), 4096))) {
      section.write(out);
    }
    byte[] record = bytes.toByteArray();
    CRC32 crc = new CRC32();
    crc.update(record, 8, record.length - 8);
    writeInt(record, 0, record.length - 8);
    writeInt(record, 4, (int) crc.getValue());
    return record;
  }

  private RegionSection decode(byte[] record) throws IOException {
    inflater.reset();
    // Buffered, as writing is: unbuffered, every byte read would be a call into the inflater.
    try (DataInputStream in =
        new DataInputStream(
            new BufferedInputStream(
                new InflaterInputStream(
                    new ByteArrayInputStream(record, 8, record.length - 8), inflater, 4096),
                4096))) {
      return RegionSection.read(in, spec);
    }
  }

  private static void writeInt(byte[] into, int at, int value) {
    into[at] = (byte) (value >>> 24);
    into[at + 1] = (byte) (value >>> 16);
    into[at + 2] = (byte) (value >>> 8);
    into[at + 3] = (byte) value;
  }

  private Path path(long file) {
    return directory.resolve(
        "r."
            + RegionSection.keyX(file)
            + "."
            + RegionSection.keyY(file)
            + "."
            + RegionSection.keyZ(file)
            + ".crg");
  }

  /** The file a section is kept in, keyed as a section is, by the file's own coordinates. */
  private static long fileKey(int sectionX, int sectionY, int sectionZ) {
    return RegionSection.key(
        Math.floorDiv(sectionX, SPAN),
        Math.floorDiv(sectionY, SPAN),
        Math.floorDiv(sectionZ, SPAN));
  }

  private static int slot(int sectionX, int sectionY, int sectionZ) {
    return (Math.floorMod(sectionX, SPAN) * SPAN + Math.floorMod(sectionY, SPAN)) * SPAN
        + Math.floorMod(sectionZ, SPAN);
  }

  /** One open file: its slots, and which of its sectors are taken. */
  private final class Open {

    final RandomAccessFile handle;
    final int[] first = new int[SLOTS];
    final int[] length = new int[SLOTS];
    final BitSet used = new BitSet();

    /** Whether the header is this domain's; a file that is not reads as empty. */
    boolean matches;

    Open(RandomAccessFile handle) throws IOException {
      this.handle = handle;
      if (handle.length() < (long) HEADER_SECTORS * SECTOR) {
        return;
      }
      byte[] header = new byte[HEADER_SECTORS * SECTOR];
      handle.seek(0);
      handle.readFully(header);
      DataInputStream in = new DataInputStream(new ByteArrayInputStream(header));
      matches =
          in.readInt() == MAGIC
              && in.readInt() == FORMAT_VERSION
              && in.readInt() == rules
              && in.readInt() == classes
              && in.readDouble() == spec.detourRatio()
              && in.readInt() == spec.shift();
      if (!matches) {
        return;
      }
      used.set(0, HEADER_SECTORS);
      for (int slot = 0; slot < SLOTS; slot++) {
        first[slot] = in.readInt();
        length[slot] = in.readInt();
        if (first[slot] >= HEADER_SECTORS && length[slot] > 0) {
          used.set(first[slot], first[slot] + sectors(length[slot]));
        } else if (first[slot] != 0 || length[slot] < -1 - spec.classCount()) {
          first[slot] = 0;
          length[slot] = 0;
        }
      }
    }

    /** Starts the file afresh with this domain's header and no sections. */
    void reset() throws IOException {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream(HEADER_SECTORS * SECTOR);
      DataOutputStream out = new DataOutputStream(bytes);
      out.writeInt(MAGIC);
      out.writeInt(FORMAT_VERSION);
      out.writeInt(rules);
      out.writeInt(classes);
      out.writeDouble(spec.detourRatio());
      out.writeInt(spec.shift());
      handle.setLength(0);
      handle.seek(0);
      handle.write(bytes.toByteArray());
      handle.setLength((long) HEADER_SECTORS * SECTOR);
      bytesWritten += (long) HEADER_SECTORS * SECTOR;
      Arrays.fill(first, 0);
      Arrays.fill(length, 0);
      used.clear();
      used.set(0, HEADER_SECTORS);
      matches = true;
    }

    /**
     * Stores a whole section in its slot alone, giving back any sectors it held, and returns how
     * many bytes were written.
     */
    long whole(int slot, int code) throws IOException {
      if (first[slot] != 0) {
        used.clear(first[slot], first[slot] + sectors(length[slot]));
      }
      setSlot(slot, 0, code);
      return 8;
    }

    /** Stores a record in a slot, returning how many bytes were written. */
    long put(int slot, byte[] record) throws IOException {
      int needed = sectors(record.length);
      int at = first[slot];
      int had = at == 0 ? 0 : sectors(length[slot]);
      if (had >= needed) {
        used.clear(at + needed, at + had); // shrunk in place: the tail is free again
      } else {
        if (at != 0) {
          used.clear(at, at + had);
        }
        at = freeRun(needed);
        used.set(at, at + needed);
      }
      handle.seek((long) at * SECTOR);
      handle.write(record);
      // Padded to the sector, so the file never ends partway through one.
      int padding = needed * SECTOR - record.length;
      if (padding > 0) {
        handle.write(new byte[padding]);
      }
      // The slot last: until it points at the new record, a crash leaves the old one in reach.
      setSlot(slot, at, record.length);
      return (long) needed * SECTOR + 8;
    }

    /** Removes a slot's section, giving its sectors back. */
    void clear(int slot) throws IOException {
      if (!matches || (first[slot] == 0 && length[slot] == 0)) {
        return;
      }
      if (first[slot] != 0) {
        used.clear(first[slot], first[slot] + sectors(length[slot]));
      }
      setSlot(slot, 0, 0);
      bytesWritten += 8;
    }

    private void setSlot(int slot, int at, int bytes) throws IOException {
      first[slot] = at;
      length[slot] = bytes;
      handle.seek(PREAMBLE + slot * 8L);
      handle.writeInt(at);
      handle.writeInt(bytes);
    }

    /** The first run of free sectors this long, or the end of the file. */
    private int freeRun(int sectors) {
      int at = used.nextClearBit(HEADER_SECTORS);
      while (true) {
        int next = used.nextSetBit(at);
        if (next < 0 || next - at >= sectors) {
          return at;
        }
        at = used.nextClearBit(next);
      }
    }
  }

  private static int sectors(int bytes) {
    return (bytes + SECTOR - 1) / SECTOR;
  }
}
