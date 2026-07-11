// Base conventions shared by every Odyssey JVM module: Java 21, checkstyle, license headers,
// strict-ish compilation, and JUnit 5. Published library modules apply odyssey.publish-conventions
// (which itself applies this plugin) instead of applying this one directly.

plugins {
    `java-library`
    checkstyle
    id("com.diffplug.spotless")
}

group = "net.whimxiqal.odyssey"
version = "0.1.0-SNAPSHOT"

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

checkstyle {
    toolVersion = "10.26.1"
    configFile = rootProject.file("config/checkstyle/checkstyle.xml")
    // Google config runs at "warning" severity; don't fail the build on style yet (Phase 0). We will
    // tighten this (severity/error, maxWarnings=0) once real code lands.
    isIgnoreFailures = true
}

spotless {
    java {
        target("src/**/*.java")
        licenseHeaderFile(rootProject.file("gradle/license-header.txt"))
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
