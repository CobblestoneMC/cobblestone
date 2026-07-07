// sponge-16-api — SpongeAPI 16-flavored developer façade (ServerPlayer, ServerLocation, …). (design/05)
plugins {
    id("odyssey.publish-conventions")
}

dependencies {
    api(project(":minecraft-api"))
    // TODO(Phase 7): compileOnly(spongeapi) — provided by the server at runtime.
}
