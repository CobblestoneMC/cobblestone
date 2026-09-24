// stonebrick-bench — runs the corpus, records what each scenario cost, and compares a run against
// the committed baselines. Not published; not shipped. (designs/stonebrick.md §8)
plugins {
    id("cobblestone.java-conventions")
    application
}

dependencies {
    implementation(project(":stonebrick:stonebrick-platform"))
    implementation(project(":core"))
    // Scenarios are hand-authored, so they are YAML rather than a bespoke format: comments and
    // readable nesting matter more here than they do for machine-written files. SnakeYAML also
    // parses JSON, so results and baselines — written by us, with deliberate key order — are read
    // back by the same parser.
    implementation(libs.snakeyaml)
    compileOnly("org.jetbrains:annotations:24.0.1")
}

application {
    mainClass = "org.cobblestonemc.stonebrick.bench.BenchMain"
}
