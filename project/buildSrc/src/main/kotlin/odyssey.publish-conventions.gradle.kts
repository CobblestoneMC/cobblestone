// Applied by library modules that publish to Maven. Extends the base java conventions and wires a
// standard Maven publication (thin jar + sources). No remote repository / signing yet — that is
// configured when we first cut a release. See design/01-modules-and-build.md for the published set
// (note: minecraft-api and minecraft are published but flagged internal, because the platform
// artifacts that ARE supported compile against them and Maven publishing must be dependency-closed).

plugins {
    id("odyssey.java-conventions")
    `maven-publish`
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
