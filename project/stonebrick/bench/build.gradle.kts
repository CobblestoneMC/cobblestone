// stonebrick-bench — runs the corpus, records what each scenario cost, and compares a run against
// the committed baselines. Not published; not shipped. (designs/stonebrick.md §8)
plugins {
    id("cobblestone.java-conventions")
    application
}

dependencies {
    implementation(project(":stonebrick:stonebrick-platform"))
    implementation(project(":core"))
    implementation(project(":minecraft:minecraft-core"))
    // Scenarios are hand-authored, so they are YAML rather than a bespoke format: comments and
    // readable nesting matter more here than they do for machine-written files. SnakeYAML also
    // parses JSON, so results and baselines — written by us, with deliberate key order — are read
    // back by the same parser.
    implementation(libs.snakeyaml)
    compileOnly("org.jetbrains:annotations:24.0.1")
}

application {
    mainClass = "org.cobblestonemc.stonebrick.bench.BenchMain"
    // A solve at a raised cell cap holds its whole node table; the default heap is not enough to
    // find out whether such a route is solvable at all.
    applicationDefaultJvmArgs = listOf("-Xmx8G")
}

// Generates the benchmark corpus by running a real, seeded Minecraft server and capturing from it.
// Captures are too large for version control, so without this every developer's terrain is whatever
// they happened to walk to — which makes a baseline meaningless to anyone else. Terrain from a seed
// is the same everywhere, so only the block data stays local and it is regenerable on demand.
tasks.register<JavaExec>("captureCorpus") {
    group = "stonebrick"
    description = "Generates missing corpus captures from the seeded world (needs -PacceptMinecraftEula=true)"
    dependsOn(":stonebrick:stonebrick-copier:shadowJar")
    mainClass = "org.cobblestonemc.stonebrick.bench.CorpusProvisioner"
    classpath = sourceSets["main"].runtimeClasspath

    // Modern Paper needs a newer Java than this build compiles with, so the toolchain resolves one
    // rather than trusting whatever `java` happens to be on PATH.
    val serverJava = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }

    val corpusRoot = layout.projectDirectory.dir("../data")
    val workDir = layout.buildDirectory.dir("corpus-server")
    val copierJar =
        project(":stonebrick:stonebrick-copier").layout.buildDirectory.file("libs/StonebrickCopier-${project.version}.jar")

    doFirst { workDir.get().asFile.mkdirs() }
    argumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                corpusRoot.asFile.absolutePath,
                workDir.get().asFile.absolutePath,
                copierJar.get().asFile.absolutePath,
                (project.findProperty("acceptMinecraftEula") ?: "false").toString(),
                serverJava.get().executablePath.asFile.absolutePath,
            )
        },
    )
}

