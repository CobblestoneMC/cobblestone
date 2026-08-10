// example-warps — a self-contained example integration plugin. It shows a third-party developer how
// to teach Odyssey new travel routes: register a PaperTransitionProvider that surfaces named "warps"
// (walk to the warp anchor, run /warp <name> to teleport) as cross-world graph edges.
//
// Deliberately lightweight: it compiles against Odyssey's published API only and ships a *thin* jar
// with no shading. The odyssey.* API classes it references are provided at runtime by the Odyssey
// plugin, which this plugin depends on (see paper-plugin.yml). This is exactly what a real integration
// (OdysseyEssentials, OdysseyCitizens, …) would do.

plugins {
    id("odyssey.java-conventions")
}

java {
    // Match the Paper platform toolchain (the server API targets a modern JDK).
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
    // Odyssey's transition-provider API. compileOnly: the classes are inside the Odyssey plugin jar at
    // runtime, so we must not bundle our own copy (that would split the type identity across loaders).
    compileOnly(project(":minecraft:platform:paper:paper-api"))
    // The server API (Bukkit/Paper), also provided at runtime.
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.+")
}
