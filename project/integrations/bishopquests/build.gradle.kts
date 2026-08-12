// odyssey-bishopquests — connects LMBishop's Quests to Odyssey. When a player tracks a quest, it
// starts (or replaces) a guided Odyssey trip to that quest's current "position" objective; it also
// surfaces each started quest's position objective as a `/navigate quests quest <name>` destination.
// A thin, un-shaded jar: it compiles against Odyssey's published API and the Quests API, both
// provided at runtime.
//
// DEPENDENCY NOTE: LMBishop's Quests documents an official Maven repo (repo.leonardobishop.com), but
// it was unreachable when this module was written, and the project's JitPack build publishes no
// consumable artifact. So the API is VENDORED in libs/ (the plain `common` + `bukkit` module jars,
// built from source) — the same approach the plugin's own build takes for third-party APIs, and
// outage-proof. To switch to Maven when the repo is back: drop the libs and use
// `compileOnly("com.leonardobishop:quests:<version>")` from `https://repo.leonardobishop.com/releases/`.

plugins {
    id("odyssey.java-conventions")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencies {
    // Odyssey's published API — provided at runtime by the Odyssey plugin, so compileOnly only.
    compileOnly(project(":minecraft:platform:paper:paper-api"))
    compileOnly(project(":minecraft:platform:paper:paper-plugin-api"))
    // The server API (Bukkit/Paper) and LMBishop Quests, both provided at runtime. Quests is vendored
    // (see note above): common carries the player/quest/task model, bukkit the tracking event.
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.+")
    compileOnly(files("libs/quests-common-3.16.1.jar"))
    compileOnly(files("libs/quests-bukkit-3.16.1.jar"))
    // Quests' API signatures carry JetBrains @NotNull/@Nullable; provide them so javac resolves them.
    compileOnly("org.jetbrains:annotations:24.0.1")
}
