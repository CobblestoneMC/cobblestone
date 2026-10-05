// core api — pure, Minecraft-agnostic navigation contract. No third-party runtime deps.
plugins {
    id("cobblestone.publish-conventions")
}

mavenPublishing {
    pom {
        name.set("Cobblestone API")
        description.set("Cobblestone API")
    }
}