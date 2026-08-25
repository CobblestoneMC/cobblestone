// sponge-12-plugin — the shippable SpongeAPI plugin (shaded uberjar), floor API 12. Not published. (design/07)
// Sponge has no Maven library resolver, so the config parser and JDBC drivers are shaded into the
// jar; SpongeAPI, Adventure, Guice, and Log4j are provided by the server. (design/07)
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("cobblestone.java-conventions")
    alias(libs.plugins.shadow)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    maven {
        name = "sponge"
        url = uri("https://repo.spongepowered.org/repository/maven-public/")
    }
}

dependencies {
    implementation(project(":minecraft:platform:sponge-12:sponge-12-core"))
    implementation(project(":minecraft:platform:sponge-12:sponge-12-plugin-api"))
    implementation(project(":minecraft:plugin:plugin-core"))
    // Bundled at runtime (no Sponge library resolver): YAML config parser + embedded JDBC drivers.
    implementation(libs.snakeyaml)
    implementation(libs.h2)
    // bStats metrics — shaded into the plugin jar and relocated (below), not server-provided.
    implementation(libs.bstats.sponge)
    // Provided by the server at runtime.
    compileOnly(libs.spongeapi)
    compileOnly(libs.adventure.api)
}

// The shaded jar is the shippable artifact: it is the only one named `Cobblestone-Sponge-<version>-all.jar`,
// so CI (and admins) can identify it by name alone rather than by ruling other jars out.
tasks.named<ShadowJar>("shadowJar") {
    archiveBaseName.set("Cobblestone-Sponge")
    archiveClassifier.set("")

    relocate("org.yaml.snakeyaml", "org.cobblestonemc.libs.snakeyaml")
    relocate("org.bstats", "org.cobblestonemc.libs.bstats")
    relocate("org.h2", "org.cobblestonemc.libs.h2")
}

tasks.named("build") {
    dependsOn("shadowJar")
}

// The plugin metadata carries the project version, rather than a copy that drifts out of date.
tasks.processResources {
    val props = mapOf(
        "projectVersion" to project.version,
    )

    inputs.properties(props)
    filesMatching("**/sponge_plugins.json") {
        expand(props)
    }
}
