// cobblestone-towny — connects Towny to Cobblestone. It surfaces towns/plots/outposts as navigable
// destinations, the town/nation/outpost spawns as COMMAND transitions, and Towny's build protection
// as a breakability check so routes avoid land the player may not dig. A thin, un-shaded jar.

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
        name = "glaremasters"
        url = uri("https://repo.glaremasters.me/repository/towny/")
    }
}

dependencies {
    // Cobblestone's published API — provided at runtime by the Cobblestone plugin, so compileOnly only.
    compileOnly(project(":minecraft:platform:paper:paper-api"))
    compileOnly(project(":minecraft:platform:paper:paper-plugin-api"))
    // The server API (Bukkit/Paper) and Towny, both provided at runtime.
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.+")
    compileOnly("com.palmergames.bukkit.towny:towny:0.103.1.1")
}
