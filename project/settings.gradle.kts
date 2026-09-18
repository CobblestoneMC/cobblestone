pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        // The Typewriter module-plugin (for the cobblestone-typewriter extension). The 2.1.x line (for
        // engine 0.9) currently lives on the beta channel.
        maven("https://maven.typewritermc.com/releases")
        maven("https://maven.typewritermc.com/beta")
    }
}

rootProject.name = "cobblestone"

// All subprojects live flat under project/ and are named exactly as in design/01-modules-and-build.md.
// Integration plugins (CobblestoneCitizens, CobblestoneEssentials, …) are added in Phase 8; they depend on
// third-party plugin APIs not yet wired here.
include(":api")
include(":core")
include(":core-test")
include(":playground")
include(":minecraft:api")
project(":minecraft:api").name = "minecraft-api"
include(":minecraft:core")
project(":minecraft:core").name = "minecraft-core"
include(":minecraft:plugin:api")
project(":minecraft:plugin:api").name = "plugin-api"
include(":minecraft:plugin:core")
project(":minecraft:plugin:core").name = "plugin-core"
include(":minecraft:platform:paper:api")
project(":minecraft:platform:paper:api").name = "paper-api"
include(":minecraft:platform:paper:core")
project(":minecraft:platform:paper:core").name = "paper-core"
include(":minecraft:platform:paper:plugin-api")
project(":minecraft:platform:paper:plugin-api").name = "paper-plugin-api"
include(":minecraft:platform:paper:plugin")
project(":minecraft:platform:paper:plugin").name = "paper-plugin"
// Sponge is split the way Paper is not (yet) forced to be: everything that does not depend on a
// particular Minecraft version lives once, in :minecraft:platform:sponge, compiled against the
// SpongeAPI floor. Each supported Minecraft version then adds only what it must — the server
// internals it reads chunks with, and a plugin entry point to ship them — under its own directory.
// `sponge-latest` tracks whatever Minecraft is current and is re-pointed as that moves; a version
// we pin for the long term gets a directory named after it, like sponge-1_21_1. The jars carry the
// Minecraft version, so `latest` is never what an admin sees.
//
// Those per-version modules hold no code at all — only a build file and plugin metadata. Reading
// chunks off disk turned out to need nothing from Minecraft's own classes, so the whole plugin
// compiles once, and what differs between the jars is which SpongeAPI they declare and what they
// are called. A version that ever does need code of its own gains a source directory then.
include(":minecraft:platform:sponge:api")
project(":minecraft:platform:sponge:api").name = "sponge-api"
include(":minecraft:platform:sponge:core")
project(":minecraft:platform:sponge:core").name = "sponge-core"
include(":minecraft:platform:sponge:plugin-api")
project(":minecraft:platform:sponge:plugin-api").name = "sponge-plugin-api"
include(":minecraft:platform:sponge:plugin")
project(":minecraft:platform:sponge:plugin").name = "sponge-plugin"
include(":minecraft:platform:sponge-latest:plugin")
project(":minecraft:platform:sponge-latest:plugin").name = "sponge-latest-plugin"
include(":minecraft:platform:sponge-1_21_1:plugin")
project(":minecraft:platform:sponge-1_21_1:plugin").name = "sponge-1_21_1-plugin"

// Example integration plugins live under examples/ — self-contained, third-party-style plugins that
// depend only on Cobblestone's published API to demonstrate extending navigation (design/08).
include(":examples:paper-warps")
project(":examples:paper-warps").name = "example-warps"

// Real integration plugins live under integrations/ — each connects one third-party plugin to Cobblestone.
include(":minecraft:integrations:essentials")
project(":minecraft:integrations:essentials").name = "cobblestone-essentials"
include(":minecraft:integrations:towny")
project(":minecraft:integrations:towny").name = "cobblestone-towny"
include(":minecraft:integrations:pikamugquests")
project(":minecraft:integrations:pikamugquests").name = "cobblestone-pikamugquests"
include(":minecraft:integrations:beautyquests")
project(":minecraft:integrations:beautyquests").name = "cobblestone-beautyquests"
include(":minecraft:integrations:betonquest")
project(":minecraft:integrations:betonquest").name = "cobblestone-betonquest"

// disabled until bishop quests provides accessible API via a maven repo
//include(":integrations:bishopquests")
//project(":integrations:bishopquests").name = "cobblestone-bishopquests"

// A Typewriter extension (Kotlin), not a Bukkit plugin — built with Typewriter's own module-plugin.
include(":minecraft:integrations:typewriter")
project(":minecraft:integrations:typewriter").name = "cobblestone-typewriter"
include(":minecraft:integrations:citizens")
project(":minecraft:integrations:citizens").name = "cobblestone-citizens"
