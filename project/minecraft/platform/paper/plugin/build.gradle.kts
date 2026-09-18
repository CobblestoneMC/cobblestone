// paper-plugin — the shippable Paper/Folia plugin. Only our own org.cobblestonemc.* modules are
// shaded into the jar (they are not on a public Maven repo); third-party runtime libraries (SnakeYAML
// now; JDBC/bStats/etc. later) are declared in the paper-plugin.yml loader and downloaded by Paper's
// MavenLibraryResolver at runtime. Adventure and paper-api are provided by the server. (design/07)

plugins {
    id("cobblestone.paper-plugin-conventions")
    alias(libs.plugins.shadow)
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
    implementation(project(":minecraft:platform:paper:paper-core"))
    implementation(project(":minecraft:platform:paper:paper-plugin-api"))
    implementation(project(":minecraft:plugin:plugin-core"))
    // bStats is bundled into the plugin jar (and relocated below), not server-provided.
    implementation(libs.bstats.bukkit)
    // Provided by the server at runtime (Adventure is bundled with Paper); the loader also references
    // Paper's Maven-resolver types, which live in paper-api.
    compileOnly(libs.paper.api)
    compileOnly(libs.adventure.api)
}

// The Minecraft version this jar is for, shared with the other "latest" endpoints so they cannot
// disagree about what current means.
val minecraftVersion = libs.versions.minecraftLatest.get()

// The shaded jar is named for the Minecraft version rather than the module, so an admin can tell at
// a glance whether `Cobblestone-Paper-<mc>-<version>.jar` matches their server.
tasks.shadowJar {
    archiveBaseName.set("Cobblestone-Paper-$minecraftVersion")
    archiveClassifier.set("")
    // Concatenate META-INF/services/* across all shaded modules so no ServiceLoader provider
    // (e.g. our CobblestoneApi) is dropped when they merge into the single uberjar.
    mergeServiceFiles()
    relocate("org.bstats", "org.cobblestonemc.libs.bstats")
}

// Make the shaded plugin jar part of the normal build, so a single `./gradlew build` at the repo root
// produces every shippable plugin jar without a separate command.
tasks.named("assemble") {
    dependsOn(tasks.named("shadowJar"))
}
