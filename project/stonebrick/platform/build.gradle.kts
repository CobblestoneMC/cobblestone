// stonebrick-platform — the PlatformApi implementation that reads captures instead of a server.
// Pure Java by design: no Bukkit, no Sponge, nothing that needs a Minecraft runtime. That is what
// makes a benchmark reproducible on any machine and what keeps the simulated IO model the only
// controllable cost in a run. (designs/stonebrick.md §5)
plugins {
    id("cobblestone.java-conventions")
}

dependencies {
    api(project(":stonebrick:stonebrick-format"))
    api(project(":minecraft:minecraft-core"))
    api(project(":minecraft:minecraft-api"))
    implementation(project(":core"))
    compileOnly("org.jetbrains:annotations:24.0.1")
}
