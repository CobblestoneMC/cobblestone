import org.jreleaser.model.Active

// Applied by library modules that publish to Maven. Extends the base java conventions and wires a
// standard Maven publication (thin jar + sources). No remote repository / signing yet — that is
// configured when we first cut a release. See design/01-modules-and-build.md for the published set
// (note: minecraft-api and minecraft are published but flagged internal, because the platform
// artifacts that ARE supported compile against them and Maven publishing must be dependency-closed).

plugins {
    id("cobblestone.java-conventions")
    `maven-publish`
    id("org.jreleaser")
}

java {
    withJavadocJar()
}

var apiVersion = "${project.property("apiVersion")}-SNAPSHOT"

publishing {
    publications {
        create<MavenPublication>("maven") {
            version = apiVersion
            from(components["java"])

            pom {
                name = "Cobblestone"
                url = "https://github.com/cobblestonemc/cobblestone"
                description = "A Minecraft server-side navigation tool"
                licenses {
                    license {
                        name.set("The MIT License")
                        url.set("https://opensource.org/license/mit")
                    }
                }
                developers {
                    developer {
                        name.set("whimxiqal")
                    }
                }
                scm {
                    url.set("https://github.com/cobblestonemc/cobblestone")
                }
            }
        }
    }

    repositories {
        maven {
            name = "Staging"
            url = uri(layout.buildDirectory.dir("staging-deploy"))
        }
    }
}

jreleaser {
    gitRootSearch = true
    signing {
        pgp {
            active.set(Active.ALWAYS)
        }
    }

    deploy {
        maven {
            mavenCentral {
                create("sonatype") {
                    active.set(Active.RELEASE)
                    // Configured explicitly to use the modern Central Portal API endpoint
                    url.set("https://central.sonatype.com/api/v1/publisher")
                    stagingRepository(layout.buildDirectory.dir("staging-deploy").get().asFile.absolutePath)
                }
            }
            nexus2 {
                create("snapshot-deploy") {
                    active.set(Active.SNAPSHOT)
                    version = apiVersion
                    snapshotUrl.set("https://central.sonatype.com/repository/maven-snapshots/")
                    applyMavenCentralRules = true
                    snapshotSupported = true
                    closeRepository = true
                    releaseRepository = true
                    stagingRepository(layout.buildDirectory.dir("staging-deploy").get().asFile.absolutePath)
                }
            }
        }
    }
}