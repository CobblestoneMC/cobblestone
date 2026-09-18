plugins {
    id("cobblestone.java-conventions")
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.23"
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
    api(project(":minecraft:minecraft-core"))
    api(project(":minecraft:platform:paper:paper-api"))
    // Provided by the server at runtime; Adventure comes bundled with Paper.
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.+")
    paperweight.paperDevBundle("26.2.build.+")
}
