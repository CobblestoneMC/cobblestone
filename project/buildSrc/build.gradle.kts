plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    // Marker artifact so the precompiled convention plugins can apply Spotless via `plugins { id(...) }`.
    implementation("com.diffplug.spotless:spotless-plugin-gradle:8.8.0")
    implementation("org.jreleaser:jreleaser-gradle-plugin:1.25.0")

    implementation("org.eclipse.jgit:org.eclipse.jgit") {
        version {
            strictly("5.13.0.202109080827-r")
        }
    }
}
