// odyssey-typewriter — an Odyssey integration for Typewriter, written as a Typewriter *extension*
// (not a Bukkit plugin) since Typewriter has its own extension system. It adds web-panel entries that
// let content creators guide *players* with Odyssey (e.g. a "Navigate Player" action). Typewriter's
// own RoadNetwork stays responsible for guiding NPCs; Odyssey handles the player-facing trips.
//
// Built with Typewriter's module-plugin (Kotlin + KSP), so it does NOT use odyssey.java-conventions.
// The output jar goes in `plugins/Typewriter/extensions/`, and `paper { dependency("odyssey") }` both
// requires Odyssey and makes its API classes reachable at runtime.

plugins {
    kotlin("jvm") version "2.2.10"
    id("com.typewritermc.module-plugin") version "2.2.0"
}

group = "net.whimxiqal.odyssey"
version = "0.1.0"

repositories {
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencies {
    // Odyssey's published API — provided at runtime by the Odyssey plugin (required via the extension
    // config below), so compileOnly. Pulls the trip service + navigator settings facades.
    compileOnly(project(":minecraft:platform:paper:paper-plugin-api"))
}

typewriter {
    namespace = "whimxiqal"

    extension {
        name = "Odyssey"
        shortDescription = "Guide players with Odyssey's pathfinding."
        description = """
            |Adds actions to guide players to a location using Odyssey's navigation, drawing a live
            |trail from the player to their target. Useful for pointing players at a quest objective,
            |an NPC, or any place they need to reach — Typewriter's RoadNetwork still guides NPCs.
            """.trimMargin()
        engineVersion = "0.9.0"
        // The 2.1.x module-plugin resolves the engine from the beta channel.
        channel = com.typewritermc.moduleplugin.ReleaseChannel.BETA

        paper {
            // Odyssey must be installed; this also makes its classes available to the extension.
            dependency("odyssey")
        }
    }
}

kotlin {
    // Use a JDK 25 toolchain so we can read Odyssey's paper-plugin-api (Java 25 bytecode). Kotlin
    // 2.2.10 caps its output at target 24, so pin the Java task to 24 too for a consistent target;
    // 24 bytecode runs fine on the Java 25 JVM the server uses for Odyssey.
    jvmToolchain(25)
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 24
}
