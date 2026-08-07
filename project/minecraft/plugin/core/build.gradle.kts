// minecraft-plugin — shared, platform-neutral plugin behavior (config, i18n, and — as later
// sub-phases land — data layer, waypoints, trips, command helpers). Not published; the platform
// plugins bundle it. (design/06)
plugins {
    id("odyssey.java-conventions")
}

dependencies {
    implementation(project(":minecraft:minecraft-core"))
    api(project(":minecraft:plugin:plugin-api"))
    // Adventure (Components/Audience) and SnakeYAML are provided at runtime by the platform plugin —
    // Adventure by the server, SnakeYAML by the paper-plugin loader (MavenLibraryResolver).
    compileOnly(libs.adventure.api)
    compileOnly(libs.snakeyaml)
    // JDBC drivers are runtime-downloaded by the platform plugin in production; here they only back
    // the DataStore contract test, which runs against both embedded engines.
    testImplementation(libs.sqlite.jdbc)
    testImplementation(libs.h2)
}
