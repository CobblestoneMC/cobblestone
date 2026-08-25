// cobblestone-bishopquests — connects LMBishop's Quests to Cobblestone. When a player tracks a quest, it
// starts (or replaces) a guided Cobblestone trip to that quest's current "position" objective; it also
// surfaces each started quest's position objective as a `/navigate quests quest <name>` destination.
// A thin, un-shaded jar: it compiles against Cobblestone's published API and the Quests API, both
// provided at runtime.

plugins {
    id("cobblestone.java-conventions")
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
    maven {
        name = "leonardobishop"
        url = uri("https://repo.leonardobishop.com/releases/")
    }
}

dependencies {
    // Cobblestone's published API — provided at runtime by the Cobblestone plugin, so compileOnly only.
    compileOnly(project(":minecraft:platform:paper:paper-api"))
    compileOnly(project(":minecraft:platform:paper:paper-plugin-api"))
    // The server API (Bukkit/Paper) and LMBishop Quests, both provided at runtime. Quests is vendored
    // (see note above): common carries the player/quest/task model, bukkit the tracking event.
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.+")
    compileOnly("com.leonardobishop:quests:v3.16.1")
    // Quests' API signatures carry JetBrains @NotNull/@Nullable; provide them so javac resolves them.
    compileOnly("org.jetbrains:annotations:24.0.1")
}

tasks.jar {
    archiveBaseName.set("CobblestoneBishopQuests")
}
