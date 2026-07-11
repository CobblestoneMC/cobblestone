// folia-plugin — the shippable Paper/Folia plugin (shaded uberjar). Not published as a library. (design/07)
// TODO(Phase 6): apply GradleUp shadow; Adventure/paper-api provided; shade+relocate bStats/config/JDBC.
plugins {
    id("odyssey.java-conventions")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    implementation(project(":minecraft:platform:paper:paper-core"))
    implementation(project(":minecraft:plugin:plugin-core"))
}
