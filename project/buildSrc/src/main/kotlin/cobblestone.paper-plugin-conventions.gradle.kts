// Conventions for every module that ships as a Paper plugin

plugins {
    id("cobblestone.java-conventions")
}

tasks.processResources {
    val props = mapOf(
        "projectVersion" to project.version,
    )

    inputs.properties(props)
    filesMatching("**/paper-plugin.yml") {
        expand(props)
    }
}
