// odyssey-quests — connects PikaMug's Quests to Odyssey. When a quest's compass target updates for a
// player, it starts (or replaces) a guided Odyssey trip to that objective; it also surfaces each
// active quest's current locatable objective as a `/navigate quest <name>` destination. A thin,
// un-shaded jar: it compiles against Odyssey's published API and the Quests API, both provided at
// runtime.

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
    maven {
        name = "codemc"
        url = uri("https://repo.codemc.io/repository/maven-public/")
    }
}

dependencies {
    // Odyssey's published API — provided at runtime by the Odyssey plugin, so compileOnly only.
    compileOnly(project(":minecraft:platform:paper:paper-api"))
    compileOnly(project(":minecraft:platform:paper:paper-plugin-api"))
    // The server API (Bukkit/Paper) and Quests, both provided at runtime. Quests is pulled without
    // its transitive deps (a bundled MySQL connector etc.) that we neither use nor want on classpath:
    //   quests-api    — the Quest/Quester/Stage interfaces we read,
    //   quests-bukkit — the plugin impl carrying the compass event and BukkitQuestsPlugin.
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.+")
    compileOnly("me.pikamug.quests:quests-api:5.3.2") { isTransitive = false }
    compileOnly("me.pikamug.quests:quests-bukkit:5.3.2") { isTransitive = false }
    // Quests' API signatures carry JetBrains @NotNull/@Nullable; provide them so javac resolves them.
    compileOnly("org.jetbrains:annotations:24.0.1")
}
