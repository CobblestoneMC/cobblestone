// paper-plugin — the shippable Paper/Folia plugin. Only our own net.whimxiqal.odyssey.* modules are
// shaded into the jar (they are not on a public Maven repo); third-party runtime libraries (SnakeYAML
// now; JDBC/bStats/etc. later) are declared in the paper-plugin.yml loader and downloaded by Paper's
// MavenLibraryResolver at runtime. Adventure and paper-api are provided by the server. (design/07)
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("odyssey.java-conventions")
    alias(libs.plugins.shadow)
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
    implementation(project(":minecraft:platform:paper:paper-core"))
    implementation(project(":minecraft:platform:paper:paper-plugin-api"))
    implementation(project(":minecraft:plugin:plugin-core"))
    // Provided by the server at runtime (Adventure is bundled with Paper); the loader also references
    // Paper's Maven-resolver types, which live in paper-api.
    compileOnly(libs.paper.api)
    compileOnly(libs.adventure.api)
}

// The shaded jar is the shippable artifact; give the thin jar a classifier so they don't collide.
tasks.named<Jar>("jar") {
    archiveClassifier.set("dev")
}
tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    // Concatenate META-INF/services/* across all shaded modules so no ServiceLoader provider
    // (e.g. our OdysseyApi) is dropped when they merge into the single uberjar.
    mergeServiceFiles()
}

// Make the shaded plugin jar part of the normal build, so a single `./gradlew build` at the repo root
// produces every shippable plugin jar without a separate command.
tasks.named("assemble") {
    dependsOn(tasks.named("shadowJar"))
}
