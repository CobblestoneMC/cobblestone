// sponge-12 — SpongeAPI PlatformApi/Scheduler implementation + native object wrappers, compiled
// against the API 12 floor. (design/05)
plugins {
    id("odyssey.publish-conventions")
}

dependencies {
    api(project(":minecraft:minecraft-core"))
    api(project(":minecraft:platform:sponge-12:sponge-12-api"))
    // TODO(Phase 7): compileOnly(spongeapi) — provided by the server at runtime.
}
