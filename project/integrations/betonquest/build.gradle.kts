// cobblestone-betonquest — connects BetonQuest to Cobblestone. When a player sets their quest compass to a
// target, it starts (or replaces) a guided Cobblestone trip there; it also surfaces each of the player's
// active quest compasses as a `/navigate compass <name>` destination. A thin, un-shaded jar: it
// compiles against Cobblestone's published API and the BetonQuest API, both provided at runtime.
//
// LICENSING NOTE: BetonQuest is GPL-3.0 (the other integrations' target plugins are MIT). This module
// only compiles against BetonQuest's addon API (compileOnly) and links to it at runtime like any
// BetonQuest addon; it ships no BetonQuest code. If distributed, consider whether this module should
// carry a GPL-compatible license rather than Cobblestone's MIT.

plugins {
    id("cobblestone.java-conventions")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

// This module links against GPL-3.0 BetonQuest, so it carries the GPL header (via the module-local
// license-header.txt the shared convention picks up) and ships the GPL-3.0 text as its own LICENSE.

repositories {
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
    maven {
        name = "betonquest"
        url = uri("https://repo.betonquest.org/betonquest/")
    }
}

dependencies {
    // Cobblestone's published API — provided at runtime by the Cobblestone plugin, so compileOnly only.
    compileOnly(project(":minecraft:platform:paper:paper-api"))
    compileOnly(project(":minecraft:platform:paper:paper-plugin-api"))
    // The server API (Bukkit/Paper) and BetonQuest, both provided at runtime. BetonQuest's addon
    // surface spans three artifacts: api (the BetonQuestApi + managers), core (the compass event),
    // and lib (shared base types). Pulled without transitives we neither use nor want on classpath.
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.+")
    compileOnly("org.betonquest.betonquest:api:3.1.0") { isTransitive = false }
    compileOnly("org.betonquest.betonquest:core:3.1.0") { isTransitive = false }
    compileOnly("org.betonquest.betonquest:lib:3.1.0") { isTransitive = false }
    // BetonQuest's API signatures carry JetBrains @NotNull/@Nullable; provide them so javac resolves.
    compileOnly("org.jetbrains:annotations:24.0.1")
}
