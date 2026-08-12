// odyssey-citizens — connects Citizens to Odyssey. It surfaces the server's Citizens NPCs as
// navigable destinations (`/navigate citizens npc <name>`), so a player can walk to any NPC. A thin,
// un-shaded jar: it compiles against Odyssey's published API and the Citizens API, both provided at
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
        name = "citizens"
        url = uri("https://maven.citizensnpcs.co/repo")
    }
}

dependencies {
    // Odyssey's published API — provided at runtime by the Odyssey plugin, so compileOnly only.
    compileOnly(project(":minecraft:platform:paper:paper-api"))
    compileOnly(project(":minecraft:platform:paper:paper-plugin-api"))
    // The server API (Bukkit/Paper) and the Citizens API, both provided at runtime. Citizens only
    // publishes snapshots; pulled without transitives (it drags in server internals we don't want).
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.+")
    compileOnly("net.citizensnpcs:citizensapi:2.0.43-SNAPSHOT") { isTransitive = false }
}
