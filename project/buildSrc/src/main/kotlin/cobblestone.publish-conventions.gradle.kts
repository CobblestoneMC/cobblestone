// Applied by library modules that publish to Maven. Extends the base java conventions and wires a
// standard Maven publication (thin jar + sources). No remote repository / signing yet — that is
// configured when we first cut a release. See design/01-modules-and-build.md for the published set
// (note: minecraft-api and minecraft are published but flagged internal, because the platform
// artifacts that ARE supported compile against them and Maven publishing must be dependency-closed).

plugins {
    id("cobblestone.java-conventions")
    id("com.vanniktech.maven.publish")
}

//java {
//    withJavadocJar()
//}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
}

mavenPublishing {
    pom {
        inceptionYear.set("2026")
        url.set("https://github.com/cobblestonemc/cobblestone/")
        licenses {
            license {
                name.set("MIT")
                url.set("https://mit-license.org/")
                distribution.set("https://mit-license.org/")
            }
        }
        developers {
            developer {
                id.set("whimxiqal")
                name.set("whimxiqal")
                url.set("https://github.com/whimxiqal/")
                organization.set("CobblestoneMC")
                organizationUrl.set("https://cobblestonemc.org")
                roles.add("maintainer")
            }
        }
        scm {
            url.set("https://github.com/username/mylibrary/")
            connection.set("scm:git:git://github.com/username/mylibrary.git")
            developerConnection.set("scm:git:ssh://git@github.com/username/mylibrary.git")
        }
    }
}