// stonebrick-format — the .sbc capture codec. Pure Java, no Minecraft, no third-party runtime
// dependencies: it is the contract between the utility plugin (which runs on a server) and the benchmark
// platform (which must not have one on its classpath). See ../README.md.
plugins {
    id("cobblestone.java-conventions")
}

dependencies {
    compileOnly("org.jetbrains:annotations:24.0.1")
}
