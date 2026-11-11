import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.process.ExecOperations

/**
 * Cobblestone's version, worked out from git.
 *
 * The latest `v*` tag on main gives the release, and the number of commits on main since that tag
 * goes after a `+`. Main is squash-merged, so that number counts merged PRs.
 *
 * - `0.3.0`: the tagged commit itself, with a clean working tree.
 * - `0.3.0+4`: main, four commits past the tag.
 * - `0.3.0+4-dev`: anything that isn't on main, or has uncommitted changes. The count is how far
 *   main was where the branch left it, so a branch keeps its version while main moves on, and
 *   commits on the branch don't change it.
 *
 * SemVer ignores everything after the `+` when comparing versions, so `0.3.0+4` is neither newer nor
 * older than `0.3.0`. That's fine for builds between releases, which are never published.
 *
 * "Main" is `origin/main`, or the local `main` where there is no remote. Builds outside a git
 * checkout, or before any tag is reachable, are `0.0.0+unknown-dev`.
 */
abstract class GitVersion : ValueSource<String, GitVersion.Parameters> {
    interface Parameters : ValueSourceParameters {
        val repository: Property<File>
    }

    @get:Inject abstract val exec: ExecOperations

    override fun obtain(): String {
        val mainRef = if (git("rev-parse", "--verify", "--quiet", "origin/main") != null) "origin/main" else "main"
        val base = git("merge-base", "HEAD", mainRef) ?: return UNKNOWN
        val tag = git("describe", "--tags", "--abbrev=0", "--match", "v*", base) ?: return UNKNOWN
        val count = git("rev-list", "--count", "--first-parent", "$tag..$base")?.toIntOrNull() ?: return UNKNOWN
        val onMain = git("merge-base", "--is-ancestor", "HEAD", mainRef) != null
        val clean = git("status", "--porcelain")?.isEmpty() == true

        val release = tag.removePrefix("v")
        return when {
            !onMain || !clean -> "$release+$count-dev"
            count == 0 -> release
            else -> "$release+$count"
        }
    }

    /** The trimmed output of a git command, or null if it failed. */
    private fun git(vararg args: String): String? {
        val out = ByteArrayOutputStream()
        val result = exec.exec {
            workingDir = parameters.repository.get()
            commandLine("git", *args)
            standardOutput = out
            errorOutput = ByteArrayOutputStream()
            isIgnoreExitValue = true
        }
        return if (result.exitValue == 0) out.toString().trim() else null
    }

    private companion object {
        const val UNKNOWN = "0.0.0+unknown-dev"
    }
}
