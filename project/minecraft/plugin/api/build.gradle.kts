// minecraft-plugin-api — developer integration surface (destinations, navigators). Published as an
// internal-but-required dependency (the supported paper-plugin-api extends types from here). First
// module to use Kyori Adventure, so destination names and messages are rich Components. (design/06)
plugins {
    id("cobblestone.publish-conventions")
}

dependencies {
    api(project(":minecraft:minecraft-api"))
    // Adventure is provided by Paper/Sponge at runtime; only ever compileOnly.
    compileOnly(libs.adventure.api)
}
