// sponge-12-plugin — the shippable SpongeAPI plugin (shaded uberjar), floor API 12. Not published. (design/07)
// Sponge has no Maven library resolver, so the config parser and JDBC drivers are shaded into the
// jar; SpongeAPI, Adventure, Guice, and Log4j are provided by the server. (design/07)
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("odyssey.java-conventions")
    alias(libs.plugins.shadow)
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
    implementation(project(":minecraft:platform:sponge-12:sponge-12-core"))
    implementation(project(":minecraft:platform:sponge-12:sponge-12-plugin-api"))
    implementation(project(":minecraft:plugin:plugin-core"))
    // Bundled at runtime (no Sponge library resolver): YAML config parser + embedded JDBC drivers.
    implementation(libs.snakeyaml)
    implementation(libs.sqlite.jdbc)
    implementation(libs.h2)
    // Provided by the server at runtime.
    compileOnly(libs.spongeapi)
    compileOnly(libs.adventure.api)
}

// The shaded jar is the shippable artifact; give the thin jar a classifier so they don't collide.
tasks.named<Jar>("jar") {
    archiveClassifier.set("dev")
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    // Relocate SnakeYAML so our copy never clashes with another plugin's. JDBC drivers are NOT
    // relocated: they self-register with DriverManager by their real class names (and SQLite loads a
    // native lib), which relocation would break.
    relocate("org.yaml.snakeyaml", "net.whimxiqal.odyssey.libs.snakeyaml")
}

tasks.named("build") {
    dependsOn("shadowJar")
}
