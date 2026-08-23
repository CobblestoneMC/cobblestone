/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper.plugin;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;

/**
 * Cobblestone's {@link PluginLoader}: it adds the third-party runtime libraries the plugin needs to
 * the plugin classpath, so they do not have to be shaded into the jar. Paper downloads them via its
 * Maven resolver on startup.
 */
@SuppressWarnings("UnstableApiUsage")
public class PaperCobblestoneLoader implements PluginLoader {

  @Override
  public void classloader(PluginClasspathBuilder classpathBuilder) {
    MavenLibraryResolver resolver = new MavenLibraryResolver();
    resolver.addDependency(new Dependency(new DefaultArtifact("org.yaml:snakeyaml:2.3"), null));
    resolver.addDependency(
        new Dependency(new DefaultArtifact("org.xerial:sqlite-jdbc:3.46.1.3"), null));
    resolver.addDependency(new Dependency(new DefaultArtifact("com.h2database:h2:2.2.224"), null));
    // Use Paper's Maven Central mirror to avoid rate limits (see the Paper plugin loader docs).
    resolver.addRepository(
        new RemoteRepository.Builder(
                "central", "default", MavenLibraryResolver.MAVEN_CENTRAL_DEFAULT_MIRROR)
            .build());
    classpathBuilder.addLibrary(resolver);
  }
}
