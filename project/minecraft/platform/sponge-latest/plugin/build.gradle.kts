// sponge-latest-plugin — the shippable SpongeAPI plugin for 26.3 (shaded uberjar). Not published.
//
// It holds no code — only this file and the plugin metadata. The whole plugin lives in
// :minecraft:platform:sponge and compiles once; this module decides which SpongeAPI the jar
// declares and what the jar is called. Sponge has no Maven library resolver, so the config parser
// and JDBC drivers are shaded in; SpongeAPI, Adventure, Guice, and Log4j are provided by the
// server. (design/07)
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("cobblestone.java-conventions")
    alias(libs.plugins.shadow)
}

// Declared for the record rather than for effect: this module compiles nothing, so the toolchain
// that matters is :minecraft:platform:sponge's, which is Java 21 — low enough for Minecraft
// 1.21.1, and Java is happy to run that bytecode on the newer JVM this jar targets.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    maven {
        name = "sponge"
        url = uri("https://repo.spongepowered.org/repository/maven-public/")
    }
}

// The Minecraft version this jar is for. It names the artifact and is written into the plugin
// metadata, so the two can never disagree about what this jar runs on.
val minecraftVersion = libs.versions.minecraftLatest.get()
val spongeApiVersion = "17.0.0"

dependencies {
    implementation(project(":minecraft:platform:sponge:sponge-plugin"))
    // Provided by the server at runtime.
    compileOnly(libs.sponge.latest)
    compileOnly(libs.adventure.api)
}

// The shaded jar is the shippable artifact, and it is named for the Minecraft version rather than
// for this module — an admin downloads `Cobblestone-Sponge-26.3-<version>.jar` and can tell at a
// glance whether it matches their server.
tasks.named<ShadowJar>("shadowJar") {
    archiveBaseName.set("Cobblestone-Sponge-$minecraftVersion")
    archiveClassifier.set("")

    relocate("org.yaml.snakeyaml", "org.cobblestonemc.libs.snakeyaml")
    relocate("org.bstats", "org.cobblestonemc.libs.bstats")
    relocate("org.h2", "org.cobblestonemc.libs.h2")
}

tasks.named("build") {
    dependsOn("shadowJar")
}

// The plugin metadata carries the project and SpongeAPI versions, rather than copies that drift.
tasks.processResources {
    val props = mapOf(
        "projectVersion" to project.version,
        "spongeApiVersion" to spongeApiVersion,
    )

    inputs.properties(props)
    filesMatching("**/sponge_plugins.json") {
        expand(props)
    }
}
