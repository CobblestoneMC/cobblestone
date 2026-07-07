// minecraft-plugin-api — developer integration surface (destinations, navigators). Not published in
// v1. First module to use Kyori Adventure. (design/06)
plugins {
    id("odyssey.java-conventions")
}

dependencies {
    api(project(":minecraft-api"))
    // TODO(Phase 6): compileOnly(adventure-api) — provided by Paper/Sponge at runtime.
}
