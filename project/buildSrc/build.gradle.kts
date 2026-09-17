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
    implementation("com.vanniktech:gradle-maven-publish-plugin:0.37.0")

    implementation("org.eclipse.jgit:org.eclipse.jgit") {
        version {
            strictly("5.13.0.202109080827-r")
        }
    }
}
