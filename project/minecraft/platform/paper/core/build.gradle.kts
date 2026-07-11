plugins {
    id("odyssey.publish-conventions")
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
}
