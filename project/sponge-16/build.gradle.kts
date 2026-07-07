// sponge-16 — SpongeAPI 16 PlatformApi/Scheduler implementation + native object wrappers. (design/05)
plugins {
    id("odyssey.publish-conventions")
}

dependencies {
    api(project(":minecraft"))
    api(project(":sponge-16-api"))
    // TODO(Phase 7): compileOnly(spongeapi) — provided by the server at runtime.
}
