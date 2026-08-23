// minecraft — the Minecraft world model + concrete modes (Walk/Swim/Fly/…). (design/04)
// Published but flagged internal.
plugins {
    id("cobblestone.publish-conventions")
}

dependencies {
    api(project(":core"))
    api(project(":minecraft:minecraft-api"))
    // Nullability annotations (@Nullable): compile-time hints only, not shipped/transitive.
    compileOnly("org.jetbrains:annotations:24.0.1")
}
