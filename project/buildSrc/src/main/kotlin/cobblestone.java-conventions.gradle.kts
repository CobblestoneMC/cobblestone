// Base conventions shared by every Cobblestone JVM module: Java 21, checkstyle, license headers,
// strict-ish compilation, and JUnit 5. Published library modules apply cobblestone.publish-conventions
// (which itself applies this plugin) instead of applying this one directly.

plugins {
    `java-library`
    id("com.diffplug.spotless")
}

group = "org.cobblestonemc"
version = "${project.property("apiVersion")}.${project.property("patchVersion")}-BETA"

repositories {
    mavenCentral()
}

java {
    // Default toolchain for core/minecraft libraries is Java 21 (broad consumer compatibility).
    // Platform modules that compile against a newer server API override this.
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // -Xlint everything except the noisy processing warning; keep warnings visible without failing yet.
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing"))
}

// Most modules use the project MIT header; a module may override it (e.g. a GPL integration that
// links a GPL plugin) by dropping its own `license-header.txt` in the module root.
val moduleLicenseHeader = file("license-header.txt")
val licenseHeader =
    if (moduleLicenseHeader.exists()) moduleLicenseHeader
    else rootProject.file("gradle/license-header.txt")

spotless {
    java {
        target("src/**/*.java")
        licenseHeaderFile(licenseHeader)
        googleJavaFormat()
        forbidWildcardImports()
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.12.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.register("formatAndBuild") {
    description = "Run spotlessApply and then build"
    dependsOn("spotlessApply", "build")
}

tasks.javadoc {
    // TODO remove this
    isFailOnError = false
}