// folia-api — Paper/Folia-flavored developer façade (Player, Location, …). (design/05)
plugins {
    id("odyssey.publish-conventions")
}

dependencies {
    api(project(":minecraft-api"))
    // TODO(Phase 5): compileOnly(paper-api) — provided by the server at runtime.
}
