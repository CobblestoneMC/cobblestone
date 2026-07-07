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
}
