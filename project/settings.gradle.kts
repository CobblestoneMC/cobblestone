plugins {
    // Allows Gradle to auto-provision the Java 21 toolchain if it's not already installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
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
include(":minecraft:platform:paper:plugin")
project(":minecraft:platform:paper:plugin").name = "paper-plugin"
include(":minecraft:platform:sponge-16:api")
project(":minecraft:platform:sponge-16:api").name = "sponge-16-api"
include(":minecraft:platform:sponge-16:core")
project(":minecraft:platform:sponge-16:core").name = "sponge-16-core"
include(":minecraft:platform:sponge-16:plugin")
project(":minecraft:platform:sponge-16:plugin").name = "sponge-16-plugin"
