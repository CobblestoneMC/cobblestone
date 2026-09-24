// stonebrickcopier — the Paper plugin that captures world data into the .sbc format the benchmark
// corpus is built from. Development tooling: it is never shipped and nothing depends on it.
//
// This is the ONE stonebrick module allowed to depend on :paper-core. It runs only on a live
// server, and it needs the real PaperBlock so that captured traits are byte-for-byte what
// production computes rather than a second implementation that can drift (designs/stonebrick.md §4).
// Every other stonebrick module stays pure Java with no server on its classpath; the capture
// directory is the one-way boundary between them.
plugins {
    id("cobblestone.paper-plugin-conventions")
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
    implementation(project(":stonebrick:stonebrick-format"))
    implementation(project(":minecraft:platform:paper:paper-core"))
    compileOnly(libs.paper.api)
    compileOnly(libs.adventure.api)
    compileOnly("org.jetbrains:annotations:24.0.1")
}

tasks.shadowJar {
    archiveBaseName.set("StonebrickCopier")
    archiveClassifier.set("")
    mergeServiceFiles()
}

tasks.named("assemble") {
    dependsOn(tasks.named("shadowJar"))
}
