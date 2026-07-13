// paper-plugin-api — the published Paper-native binding of the plugin-extension surface. Other Paper
// plugins depend on this to register destinations/navigators with the installed Odyssey plugin and
// reach navigation via .platform() in native Player/Location terms. (design/06, design/07)
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
    api(project(":minecraft:plugin:plugin-api"))
    api(project(":minecraft:platform:paper:paper-api"))
    // Player/Location come from the server at runtime; Adventure comes bundled with Paper.
    compileOnly(libs.paper.api)
}
