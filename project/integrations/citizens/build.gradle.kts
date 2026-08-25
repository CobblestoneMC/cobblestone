// cobblestone-citizens — connects Citizens to Cobblestone. It surfaces the server's Citizens NPCs as
// navigable destinations (`/navigate citizens npc <name>`), so a player can walk to any NPC. A thin,
// un-shaded jar: it compiles against Cobblestone's published API and the Citizens API, both provided at
// runtime.

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
        name = "citizens"
        url = uri("https://maven.citizensnpcs.co/repo")
    }
}

dependencies {
    // Cobblestone's published API — provided at runtime by the Cobblestone plugin, so compileOnly only.
    compileOnly(project(":minecraft:platform:paper:paper-api"))
    compileOnly(project(":minecraft:platform:paper:paper-plugin-api"))
    // The server API (Bukkit/Paper) and the Citizens API, both provided at runtime. Citizens only
    // publishes snapshots; pulled without transitives (it drags in server internals we don't want).
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.+")
    compileOnly("net.citizensnpcs:citizensapi:2.0.43-SNAPSHOT") { isTransitive = false }
}

tasks.jar {
    archiveBaseName.set("CobblestoneCitizens")
}
