// cobblestone-typewriter — an Cobblestone integration for Typewriter, written as a Typewriter *extension*
// (not a Bukkit plugin) since Typewriter has its own extension system. It adds web-panel entries that
// let content creators guide *players* with Cobblestone (e.g. a "Navigate Player" action). Typewriter's
// own RoadNetwork stays responsible for guiding NPCs; Cobblestone handles the player-facing trips.
//
// Built with Typewriter's module-plugin (Kotlin + KSP), so it does NOT use cobblestone.java-conventions.
// The output jar goes in `plugins/Typewriter/extensions/`, and `paper { dependency("cobblestone") }` both
// requires Cobblestone and makes its API classes reachable at runtime.

plugins {
    kotlin("jvm") version "2.3.20"
    id("com.typewritermc.module-plugin") version "2.1.0"
}

group = "org.cobblestonemc"
version = "0.1.0"

repositories {
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencies {
    // Cobblestone's published API — provided at runtime by the Cobblestone plugin (required via the extension
    // config below), so compileOnly. Pulls the trip service + navigator settings facades.
    compileOnly(project(":minecraft:platform:paper:paper-plugin-api"))
}

typewriter {
    namespace = "cobblestonemc"

    extension {
        name = "Cobblestone"
        shortDescription = "Guide players with Cobblestone's pathfinding."
        description = """
            |Adds actions to guide players to a location using Cobblestone's navigation, drawing a live
            |trail from the player to their target. Useful for pointing players at a quest objective,
            |an NPC, or any place they need to reach — Typewriter's RoadNetwork still guides NPCs.
            """.trimMargin()
        engineVersion = "0.9.0"
        // The 2.1.x module-plugin resolves the engine from the beta channel.
        channel = com.typewritermc.moduleplugin.ReleaseChannel.BETA

        paper {
            // Cobblestone must be installed; this also makes its classes available to the extension.
            dependency("cobblestone")
        }
    }
}

kotlin {
    jvmToolchain(25)
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
}
