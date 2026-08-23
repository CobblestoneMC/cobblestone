// sponge-12-plugin-api — the published Sponge-native binding of the plugin-extension surface. Other
// Sponge plugins depend on this to register destinations/navigators with the installed Cobblestone
// plugin and reach navigation in native ServerPlayer/ServerLocation terms. (design/06, design/07)
plugins {
    id("cobblestone.publish-conventions")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    maven {
        name = "sponge"
        url = uri("https://repo.spongepowered.org/repository/maven-public/")
    }
}

dependencies {
    api(project(":minecraft:plugin:plugin-api"))
    api(project(":minecraft:platform:sponge-12:sponge-12-api"))
    // ServerPlayer/ServerLocation come from the server at runtime; Adventure comes bundled with Sponge.
    compileOnly(libs.spongeapi)
    compileOnly(libs.adventure.api)
}
