plugins {
    // Allows Gradle to auto-provision the Java 21 toolchain if it's not already installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "odyssey"

// All subprojects live flat under project/ and are named exactly as in design/01-modules-and-build.md.
// Integration plugins (OdysseyCitizens, OdysseyEssentials, …) are added in Phase 8; they depend on
// third-party plugin APIs not yet wired here.
listOf(
    "core-api",
    "core",
    "core-test",
    "playground",
    "minecraft-api",
    "minecraft",
    "folia-api",
    "folia",
    "sponge-16-api",
    "sponge-16",
    "minecraft-plugin-api",
    "minecraft-plugin",
    "folia-plugin",
    "sponge-16-plugin",
).forEach { include(it) }
