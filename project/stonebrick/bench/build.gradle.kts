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

// The bench's default paths are written relative to the repository rather than to this module, so
// that the same arguments work whether they are typed at a shell or handed to Gradle.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

// Generates the benchmark corpus by running a real, seeded Minecraft server and capturing from it.
// Captures are too large for version control, so without this every developer's terrain is whatever
// they happened to walk to. A seed fixes the routes and landforms, so scenarios and the manifest are
// shared; the blocks are not byte-reproducible, so captures and baselines stay local.
tasks.register<JavaExec>("captureCorpus") {
    group = "stonebrick"
    description = "Generates missing corpus captures from the seeded world (needs -PacceptMinecraftEula=true)"
    dependsOn(":stonebrick:stonebrick-copier:shadowJar")
    mainClass = "org.cobblestonemc.stonebrick.bench.CorpusProvisioner"
    classpath = sourceSets["main"].runtimeClasspath

    // Modern Paper needs a newer Java than this build compiles with, so the toolchain finds one
    // rather than trusting whatever `java` happens to be on PATH. It only *finds* one: nothing here
    // downloads a JDK on the developer's behalf (see gradle.properties), so a machine without Java
    // 25 gets told to install it.
    val serverJava = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    fun serverJavaPath(): String =
        try {
            serverJava.get().executablePath.asFile.absolutePath
        } catch (e: Exception) {
            // Not chained: Gradle would print the toolchain error in place of this one.
            throw GradleException(
                "captureCorpus runs a Paper server, which needs a Java 25 JDK. Install one (for " +
                    "example from https://adoptium.net), then run again. If Gradle still cannot " +
                    "find it, pass -Porg.gradle.java.installations.paths=<path to the JDK>. (" +
                    e.message + ")",
            )
        }

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
                serverJavaPath(),
            )
        },
    )
}

