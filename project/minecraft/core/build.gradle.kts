// minecraft — the Minecraft world model + its movement behavior (walk/swim/fly/…).
plugins {
    id("cobblestone.java-conventions")
}

dependencies {
    api(project(":core"))
    api(project(":minecraft:minecraft-api"))
    // Nullability annotations (@Nullable): compile-time hints only, not shipped/transitive.
    compileOnly("org.jetbrains:annotations:24.0.1")
}
