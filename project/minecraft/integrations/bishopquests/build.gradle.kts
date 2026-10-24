// cobblestone-bishopquests — connects LMBishop's Quests to Cobblestone. When a player tracks a quest, it
// starts (or replaces) a guided Cobblestone trip to that quest's current "position" objective; it also
// surfaces each started quest's position objective as a `/navigate quests quest <name>` destination.
// A thin, un-shaded jar: it compiles against Cobblestone's published API and the Quests API, both
// provided at runtime.

plugins {
    id("cobblestone.paper-plugin-conventions")
}

version = "0.1.0"

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
    // LMBishop's Quests isn't published to a Maven repository; JitPack builds it from its GitHub tag.
    maven {
        name = "jitpack"
        url = uri("https://jitpack.io")
        content { includeGroup("com.github.LMBishop") }
    }
}

dependencies {
    // Cobblestone's published API — provided at runtime by the Cobblestone plugin, so compileOnly only.
    compileOnly(project(":minecraft:platform:paper:paper-api"))
    compileOnly(project(":minecraft:platform:paper:paper-plugin-api"))
    // The server API (Bukkit/Paper) and LMBishop Quests, both provided at runtime. The Quests jar is
    // the whole plugin: common carries the player/quest/task model, bukkit the tracking event.
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.+")
    compileOnly("com.github.LMBishop:Quests:3.16.1")
    // Quests' API signatures carry JetBrains @NotNull/@Nullable; provide them so javac resolves them.
    compileOnly("org.jetbrains:annotations:24.0.1")
}

tasks.jar {
    archiveBaseName.set("CobblestoneBishopQuests")
}
