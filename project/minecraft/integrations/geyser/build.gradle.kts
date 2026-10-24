// cobblestone-geyser — connects Geyser to Cobblestone. Bedrock players open the navigation menu by
// performing an emote, rather than typing /navigate. A thin, un-shaded jar: it runs Cobblestone's own
// command, so it needs no Cobblestone API at all, and Geyser's API is provided at runtime.

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
    maven {
        name = "opencollab"
        url = uri("https://repo.opencollab.dev/main/")
    }
}

dependencies {
    // The server API (Bukkit/Paper) and Geyser's API, both provided at runtime. Geyser only publishes
    // snapshots.
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.+")
    compileOnly("org.geysermc.geyser:api:2.9.5-SNAPSHOT")
}

tasks.jar {
    archiveBaseName.set("CobblestoneGeyser")
}
