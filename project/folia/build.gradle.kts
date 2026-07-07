// folia — Paper/Folia PlatformApi/Scheduler implementation + native object wrappers. (design/05)
plugins {
    id("odyssey.publish-conventions")
}

dependencies {
    api(project(":minecraft"))
    api(project(":folia-api"))
    // TODO(Phase 5): compileOnly(paper-api) — provided by the server at runtime.
}
