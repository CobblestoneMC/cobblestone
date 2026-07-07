// sponge-16-plugin — the shippable SpongeAPI 16 plugin (shaded uberjar). Not published. (design/07)
// TODO(Phase 7): apply GradleUp shadow; Adventure/spongeapi provided; shade+relocate bStats/config/JDBC.
plugins {
    id("odyssey.java-conventions")
}

dependencies {
    implementation(project(":sponge-16"))
    implementation(project(":minecraft-plugin"))
}
