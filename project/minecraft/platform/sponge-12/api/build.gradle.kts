// sponge-12-api — SpongeAPI developer façade (ServerPlayer, ServerLocation, …), compiled against the
// API 12 floor so one jar serves API 12–17 (see design notes on the Sponge versioning model). (design/05)
plugins {
    id("odyssey.publish-conventions")
}

dependencies {
    api(project(":minecraft:minecraft-api"))
    // TODO(Phase 7): compileOnly(spongeapi) — provided by the server at runtime.
}
