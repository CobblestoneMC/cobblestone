// core api — pure, Minecraft-agnostic navigation contract. No third-party runtime deps. (design/02)
plugins {
    id("cobblestone.publish-conventions")
}

mavenPublishing {
    pom {
        name.set("Cobblestone API")
        description.set("Cobblestone API")
    }
}