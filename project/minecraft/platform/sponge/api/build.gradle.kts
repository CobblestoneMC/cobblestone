// sponge-api — SpongeAPI developer façade (ServerPlayer, ServerLocation, …), compiled against the
// API floor so one jar serves every Minecraft version Cobblestone supports. Nothing here is
// version-specific; see settings.gradle.kts for how the Sponge modules are split. (design/05)
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
    compileOnly(libs.sponge.api12)
}

mavenPublishing {
    pom {
        name.set("Cobblestone Sponge API")
        description.set("Cobblestone Sponge API")
    }
}
