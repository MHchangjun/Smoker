package com.song.inspection

import java.io.File

data class InspectionFixOptions(
    val projectRoot: File,
    val profilePath: File,
    val inspectBin: String,
    val maxFindings: Int
)

class InspectionFixCommand(
    private val workingDirectory: File = File(".").absoluteFile
) {
    fun parse(args: List<String>): InspectionFixOptions {
        val positional = mutableListOf<String>()
        var projectRootArg: String? = null
        var profileArg: String? = null
        var inspectBin = "inspect.sh"
        var maxFindings = DEFAULT_MAX_FINDINGS

        args.forEach { arg ->
            when {
                arg.startsWith("--project-root=") -> projectRootArg = arg.substringAfter("=").trim()
                arg.startsWith("--profile=") -> profileArg = arg.substringAfter("=").trim()
                arg.startsWith("--inspection-profile=") -> profileArg = arg.substringAfter("=").trim()
                arg.startsWith("--inspect-bin=") -> inspectBin = arg.substringAfter("=").trim()
                arg.startsWith("--max-findings=") -> maxFindings = arg.substringAfter("=").trim().toInt()
                arg.startsWith("--") -> error("Unknown option: $arg")
                else -> positional += arg
            }
        }

        require(positional.size <= 2) {
            usage("too many positional arguments: ${positional.joinToString(" ")}")
        }

        if (projectRootArg == null && positional.isNotEmpty()) projectRootArg = positional[0]
        if (profileArg == null && positional.size >= 2) profileArg = positional[1]

        val projectRoot = projectRootArg
            ?.takeIf { it.isNotBlank() }
            ?.let { File(it).absoluteFile }
            ?: workingDirectory

        val profilePath = profileArg
            ?.takeIf { it.isNotBlank() }
            ?.let { File(it).absoluteFile }
            ?: projectRoot.resolve(".idea/inspectionProfiles/Project_Default.xml").absoluteFile

        require(projectRoot.isDirectory) { "Project root is not a directory: ${projectRoot.absolutePath}" }
        require(profilePath.isFile) { "Inspection profile not found: ${profilePath.absolutePath}" }
        require(maxFindings > 0) { "--max-findings must be positive" }

        return InspectionFixOptions(
            projectRoot = projectRoot,
            profilePath = profilePath,
            inspectBin = inspectBin,
            maxFindings = maxFindings
        )
    }

    private fun usage(reason: String): String {
        return buildString {
            appendLine(reason)
            appendLine("usage:")
            appendLine("  inspect-fix [project-root] [inspection-profile.xml] [--inspect-bin=/path/to/inspect.sh] [--max-findings=10]")
            appendLine("examples:")
            appendLine("  smoker inspect-fix")
            appendLine("  smoker inspect-fix --inspect-bin=/Users/nate/Applications/IntelliJ\\ IDEA.app/Contents/bin/inspect.sh")
        }.trim()
    }

    private companion object {
        const val DEFAULT_MAX_FINDINGS = 10
    }
}
