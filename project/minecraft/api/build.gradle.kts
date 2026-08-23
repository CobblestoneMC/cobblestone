// minecraft-api — thin, developer-facing Minecraft types (StepType, Instruction, Agent, …). (design/04)
// Published but flagged internal (platform APIs compile against it). No Adventure here.
plugins {
    id("cobblestone.publish-conventions")
}

dependencies {
    api(project(":api"))
}
