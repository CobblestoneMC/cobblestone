// cobblestone-essentials — connects EssentialsX to Cobblestone. It surfaces the player's /home and /spawn
// teleports as navigable destinations and as COMMAND transitions (a route may "use" the teleport as a
// wormhole and prompt the player to run the command). A thin, un-shaded jar: it compiles against
// Cobblestone's published API and the EssentialsX API, both provided at runtime.

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
        name = "essentialsx"
        url = uri("https://repo.essentialsx.net/releases/")
    }
}

dependencies {
    // Cobblestone's published API — provided at runtime by the Cobblestone plugin, so compileOnly only.
    compileOnly(project(":minecraft:platform:paper:paper-api"))
    compileOnly(project(":minecraft:platform:paper:paper-plugin-api"))
    // The server API (Bukkit/Paper) and EssentialsX, both provided at runtime. EssentialsX is fetched
    // without its transitive deps so its bundled server API can't clash with paper-api above.
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.+")
    compileOnly("net.essentialsx:EssentialsX:2.20.1") { isTransitive = false }
    compileOnly("net.essentialsx:EssentialsXSpawn:2.20.1") { isTransitive = false }
}

tasks.jar {
    archiveBaseName.set("CobblestoneEssentials")
}