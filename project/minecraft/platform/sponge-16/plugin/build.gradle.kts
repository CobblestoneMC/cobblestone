// sponge-16-plugin — the shippable SpongeAPI 16 plugin (shaded uberjar). Not published. (design/07)
// TODO(Phase 7): apply GradleUp shadow; Adventure/spongeapi provided; shade+relocate bStats/config/JDBC.
plugins {
    id("odyssey.java-conventions")
}

dependencies {
    implementation(project(":minecraft:platform:sponge-16:sponge-16-core"))
    implementation(project(":minecraft:plugin:plugin-core"))
}
