// minecraft-plugin — shared plugin behavior (config, data layer, waypoints, trips, commands). (design/06)
// Not published (internal glue reused by the platform plugins).
plugins {
    id("odyssey.java-conventions")
}

dependencies {
    implementation(project(":minecraft:minecraft-core"))
    api(project(":minecraft:plugin:plugin-api"))
    // TODO(Phase 6): config (YAML) lib + JDBC drivers (SQLite/H2 first); shaded+relocated by plugins.
}
