// sponge-12-api — SpongeAPI developer façade (ServerPlayer, ServerLocation, …), compiled against the
// API 12 floor so one jar serves API 12–17 (see design notes on the Sponge versioning model). (design/05)
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
    api(project(":minecraft:minecraft-api"))
    // Provided by the server at runtime; Adventure comes bundled with Sponge.
    compileOnly(libs.spongeapi)
}
