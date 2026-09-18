// sponge-core — SpongeAPI PlatformApi/Scheduler implementation + native object wrappers, compiled
// against the API floor. Version-independent: the one thing that differs per Minecraft version is
// reading an unloaded chunk, which is behind OfflineChunkSource and supplied by a per-version
// module. (design/05)
plugins {
    id("cobblestone.java-conventions")
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
    api(project(":minecraft:platform:sponge:sponge-api"))
    // Nullability annotations (@Nullable): compile-time hints only, not shipped/transitive.
    compileOnly("org.jetbrains:annotations:24.0.1")
    // Provided by the server at runtime; Adventure comes bundled with Sponge.
    compileOnly(libs.sponge.api12)
}
