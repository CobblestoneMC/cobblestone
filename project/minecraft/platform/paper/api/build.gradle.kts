plugins {
    id("cobblestone.publish-conventions")
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
    api(project(":minecraft:minecraft-api"))
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.+")
}
