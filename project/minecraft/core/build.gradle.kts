// minecraft — the Minecraft world model + concrete modes (Walk/Swim/Fly/…). (design/04)
// Published but flagged internal.
plugins {
    id("odyssey.publish-conventions")
}

dependencies {
    api(project(":core"))
    api(project(":minecraft:minecraft-api"))
}
