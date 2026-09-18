/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper;

import com.mojang.serialization.Codec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.cobblestonemc.minecraft.MinecraftBlock;
import org.cobblestonemc.minecraft.MinecraftChunk;
import org.cobblestonemc.minecraft.UnknownBlock;
import org.jetbrains.annotations.Nullable;

/**
 * A {@link MinecraftChunk} decoded straight from a saved chunk's NBT, without loading the chunk
 * into the server.
 *
 * <p>This is the whole point of the class: a search that sweeps a few thousand chunks would
 * otherwise drag every one of them into the chunk system — full deserialization into a {@code
 * LevelChunk}, block entities, lighting, tickets, unload scheduling — for the sake of reading block
 * ids. Here the chunk tag is decoded into nothing but the per-section {@link PalettedContainer}s,
 * which keep their storage in the same packed-long form the file uses, and the rest of it is
 * dropped on the floor.
 *
 * <p>The cost of that is version coupling: this reads NMS internals (the {@code sections} / {@code
 * block_states} layout) and will need revisiting whenever they move. {@link NMSSupport} is what
 * keeps a mismatch from becoming a wall of stack traces.
 *
 * <p>{@link NMSChunkReader} owns the IO; this class only decodes what it hands over. Reading the
 * <em>saved</em> chunk means edits made since it was last written are not visible — Moonrise's IO
 * does serve pending writes, so a chunk that was recently unloaded reads current, but one still
 * dirty in memory does not. Callers must therefore only take this path for chunks that aren't
 * loaded, which {@link PaperPlatformApi} does, snapshotting loaded chunks through Bukkit instead.
 *
 * <p>Instances are immutable once built and safe to read from any thread: {@link
 * PalettedContainer#get} takes no lock, and nothing here ever writes.
 */
final class NMSChunk implements MinecraftChunk {

  /** Section containers indexed by {@code sectionY - minSectionY}; {@code null} means all air. */
  private final PalettedContainer<BlockState>[] sections;

  private final int minSectionY;

  private NMSChunk(PalettedContainer<BlockState>[] sections, int minSectionY) {
    this.sections = sections;
    this.minSectionY = minSectionY;
  }

  /**
   * Decodes the section palettes out of a saved chunk tag.
   *
   * <p>Throws rather than returning a placeholder on a tag it cannot decode: the caller owns the
   * decision about what an unreadable chunk means, and distinguishing "corrupt" from "not there" is
   * the whole reason this does not answer both with the same value.
   *
   * @param level the level the chunk belongs to, for its height and block state registry
   * @param raw the chunk tag as it was saved
   * @return the decoded chunk, or {@code null} if the chunk's terrain was never finished
   * @throws RuntimeException if the tag is present but cannot be decoded
   */
  @SuppressWarnings({"unchecked"})
  static @Nullable NMSChunk parse(ServerLevel level, CompoundTag raw) {
    // Run the vanilla data fixers first: a world upgraded from an older version still has
    // untouched chunks in the old format, and their palettes won't decode as-is.
    CompoundTag tag = level.getChunkSource().chunkMap.upgradeChunkTag(raw);

    ChunkStatus status = tag.read("Status", ChunkStatus.CODEC).orElse(ChunkStatus.EMPTY);
    if (!status.isOrAfter(ChunkStatus.FULL)) {
      // Terrain isn't finished, so the blocks on disk aren't what a player would walk on.
      return null;
    }

    // The factory's codec, unlike the one SerializableChunkData uses, carries no anti-xray preset
    // states — we want the real blocks, not the obfuscated view sent to clients.
    Codec<PalettedContainer<BlockState>> codec =
        level.palettedContainerFactory().blockStatesContainerCodec();
    int minSectionY = level.getMinSectionY();
    PalettedContainer<BlockState>[] sections = new PalettedContainer[level.getSectionsCount()];

    ListTag sectionTags = tag.getListOrEmpty("sections");
    for (int i = 0; i < sectionTags.size(); i++) {
      CompoundTag sectionTag = sectionTags.getCompound(i).orElse(null);
      if (sectionTag == null) {
        continue;
      }
      int index = sectionTag.getByteOr("Y", (byte) 0) - minSectionY;
      if (index < 0 || index >= sections.length) {
        // A light-only section just outside the build height; it has no blocks to offer.
        continue;
      }
      CompoundTag blockStates = sectionTag.getCompound("block_states").orElse(null);
      if (blockStates == null) {
        continue; // left null: all air
      }
      sections[index] =
          codec.parse(NbtOps.INSTANCE, blockStates).getOrThrow(IllegalStateException::new);
    }
    return new NMSChunk(sections, minSectionY);
  }

  @Override
  public MinecraftBlock block(int localX, int y, int localZ) {
    int index = (y >> 4) - minSectionY;
    if (index < 0 || index >= sections.length) {
      return UnknownBlock.INSTANCE; // outside the build height
    }
    PalettedContainer<BlockState> section = sections[index];
    if (section == null) {
      return NMSBlocks.AIR;
    }
    return NMSBlocks.of(section.get(localX & 15, y & 15, localZ & 15));
  }
}
