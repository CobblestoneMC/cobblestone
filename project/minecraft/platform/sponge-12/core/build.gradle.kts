// sponge-12 — SpongeAPI PlatformApi/Scheduler implementation + native object wrappers, compiled
// against the API 12 floor. (design/05)
plugins {
    id("odyssey.publish-conventions")
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
    api(project(":minecraft:minecraft-core"))
    api(project(":minecraft:platform:sponge-12:sponge-12-api"))
    // Provided by the server at runtime; Adventure comes bundled with Sponge.
    compileOnly(libs.spongeapi)
}
