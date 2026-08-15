// sponge-12-plugin — the shippable SpongeAPI plugin (shaded uberjar), floor API 12. Not published. (design/07)
// TODO(Phase 7): apply GradleUp shadow; Adventure/spongeapi provided; shade+relocate bStats/config/JDBC.
plugins {
    id("odyssey.java-conventions")
}

dependencies {
    implementation(project(":minecraft:platform:sponge-12:sponge-12-core"))
    implementation(project(":minecraft:plugin:plugin-core"))
}
