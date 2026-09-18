// sponge-plugin — every piece of the Sponge plugin that does not depend on a Minecraft version:
// commands, listeners, config, the trip service, the integration registry, and the plugin lifecycle
// itself (AbstractCobblestoneSpongePlugin).
//
// This is a library, not a shippable jar. The shaded plugin jars are built by the per-version
// modules (:minecraft:platform:sponge-latest:sponge-latest-plugin and friends), each of which adds
// only an entry point and the server internals for its Minecraft version. (design/07)
plugins {
    id("cobblestone.java-conventions")
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
    api(project(":minecraft:platform:sponge:sponge-core"))
    api(project(":minecraft:platform:sponge:sponge-plugin-api"))
    api(project(":minecraft:plugin:plugin-core"))
    // Bundled into the shippable jars downstream (Sponge has no Maven library resolver), but only
    // compiled against here.
    api(libs.snakeyaml)
    api(libs.h2)
    api(libs.bstats.sponge)
    // Provided by the server at runtime.
    compileOnly(libs.sponge.api12)
    compileOnly(libs.adventure.api)
}
