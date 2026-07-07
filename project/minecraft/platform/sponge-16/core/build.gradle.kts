// sponge-16 — SpongeAPI 16 PlatformApi/Scheduler implementation + native object wrappers. (design/05)
plugins {
    id("odyssey.publish-conventions")
}

dependencies {
    api(project(":minecraft:minecraft-core"))
    api(project(":minecraft:platform:sponge-16:sponge-16-api"))
    // TODO(Phase 7): compileOnly(spongeapi) — provided by the server at runtime.
}
