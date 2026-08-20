/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.plugin.config;

import java.util.List;
import net.whimxiqal.odyssey.minecraft.ChunkLoadPolicy;

/**
 * What {@link ConfigKeys} needs to know about the platform it is registering for: the settings
 * whose default, accepted values, or documentation differ between Paper and Sponge.
 *
 * <p>Each platform plugin builds one of these at startup. Keys that exist on only one platform are
 * not described here — that platform registers them itself on the same {@link ConfigManager} (see
 * the Sponge plugin's chunk-loading keys), and they appear only in that platform's generated file.
 *
 * <p>This record grows a component per setting that diverges. Prefer keeping a setting's
 * <em>meaning</em> identical across platforms and varying only the default and the prose; a value
 * that means different things on different platforms is a trap for anyone moving a config between
 * servers.
 *
 * @param name the platform's display name, for diagnostics
 * @param chunkPolicyDefault the default for {@code search.chunks.policy}
 * @param chunkPolicies the policies this platform can honor, in the order they are documented
 * @param chunkPolicyComment the prose emitted above {@code search.chunks.policy}
 */
public record ConfigPlatform(
    String name,
    ChunkLoadPolicy chunkPolicyDefault,
    List<ChunkLoadPolicy> chunkPolicies,
    String chunkPolicyComment) {

  /** Canonical, defensive copy of the policy list. */
  public ConfigPlatform {
    chunkPolicies = List.copyOf(chunkPolicies);
  }
}
