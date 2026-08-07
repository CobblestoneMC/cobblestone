/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper.plugin;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;

/**
 * Odyssey's {@link PluginLoader}: it adds the third-party runtime libraries the plugin needs to the
 * plugin classpath, so they do not have to be shaded into the jar. Paper downloads them via its Maven
 * resolver on startup.
 *
 * <p>Currently: SnakeYAML (used by the platform-neutral config manager) and the embedded DataStore
 * JDBC drivers (SQLite, H2). Both drivers are added for now regardless of the configured backend; a
 * later pass can read the backend from config here and add only the one in use (design/06 notes only
 * the configured backend's driver need be present). bStats and Prometheus land with their subsystems.
 */
@SuppressWarnings("UnstableApiUsage")
public class PaperOdysseyLoader implements PluginLoader {

  @Override
  public void classloader(PluginClasspathBuilder classpathBuilder) {
    MavenLibraryResolver resolver = new MavenLibraryResolver();
    resolver.addDependency(new Dependency(new DefaultArtifact("org.yaml:snakeyaml:2.3"), null));
    resolver.addDependency(new Dependency(new DefaultArtifact("org.xerial:sqlite-jdbc:3.46.1.3"), null));
    resolver.addDependency(new Dependency(new DefaultArtifact("com.h2database:h2:2.2.224"), null));
    // Use Paper's Maven Central mirror to avoid rate limits (see the Paper plugin loader docs).
    resolver.addRepository(new RemoteRepository.Builder(
        "central", "default", MavenLibraryResolver.MAVEN_CENTRAL_DEFAULT_MIRROR).build());
    classpathBuilder.addLibrary(resolver);
  }
}
