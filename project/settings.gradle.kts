pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        // The Typewriter module-plugin (for the odyssey-typewriter extension). The 2.1.x line (for
        // engine 0.9) currently lives on the beta channel.
        maven("https://maven.typewritermc.com/releases")
        maven("https://maven.typewritermc.com/beta")
    }
}

rootProject.name = "odyssey"

// All subprojects live flat under project/ and are named exactly as in design/01-modules-and-build.md.
// Integration plugins (OdysseyCitizens, OdysseyEssentials, …) are added in Phase 8; they depend on
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
include(":minecraft:platform:sponge-16:api")
project(":minecraft:platform:sponge-16:api").name = "sponge-16-api"
include(":minecraft:platform:sponge-16:core")
project(":minecraft:platform:sponge-16:core").name = "sponge-16-core"
include(":minecraft:platform:sponge-16:plugin")
project(":minecraft:platform:sponge-16:plugin").name = "sponge-16-plugin"

// Example integration plugins live under examples/ — self-contained, third-party-style plugins that
// depend only on Odyssey's published API to demonstrate extending navigation (design/08).
include(":examples:paper-warps")
project(":examples:paper-warps").name = "example-warps"

// Real integration plugins live under integrations/ — each connects one third-party plugin to Odyssey.
include(":integrations:essentials")
project(":integrations:essentials").name = "odyssey-essentials"
include(":integrations:towny")
project(":integrations:towny").name = "odyssey-towny"
include(":integrations:pikamugquests")
project(":integrations:pikamugquests").name = "odyssey-pikamugquests"
include(":integrations:beautyquests")
project(":integrations:beautyquests").name = "odyssey-beautyquests"
include(":integrations:betonquest")
project(":integrations:betonquest").name = "odyssey-betonquest"
// disabled until proper jar file is available
//include(":integrations:bishopquests")
//project(":integrations:bishopquests").name = "odyssey-bishopquests"
// A Typewriter extension (Kotlin), not a Bukkit plugin — built with Typewriter's own module-plugin.
include(":integrations:typewriter")
project(":integrations:typewriter").name = "odyssey-typewriter"
include(":integrations:citizens")
project(":integrations:citizens").name = "odyssey-citizens"
