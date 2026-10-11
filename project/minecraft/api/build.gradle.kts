// minecraft-api — thin, developer-facing Minecraft types (StepType, Instruction, Agent, …).
// Published but flagged internal (platform APIs compile against it). No Adventure here.
plugins {
    id("cobblestone.publish-conventions")
}

dependencies {
    api(project(":api"))
}

mavenPublishing {
    pom {
        name.set("Cobblestone Minecraft API")
        description.set("Cobblestone Minecraft API")
    }
}