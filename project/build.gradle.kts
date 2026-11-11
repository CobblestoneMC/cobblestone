// Every module takes its version from git once, here (see GitVersion in buildSrc). A module that
// versions itself, like each integration plugin, sets its own in its build file, which runs later
// and wins.
val cobblestoneVersion = providers.of(GitVersion::class) { parameters.repository = rootDir }.get()

allprojects {
    version = cobblestoneVersion
}

tasks.register("printVersion") {
    description = "Prints the version this checkout builds as"
    val printed = cobblestoneVersion
    doLast { println(printed) }
}
