package com.song.inspection

import com.song.workflow.CommitOutcome
import com.song.workflow.GitCli
import java.io.File

internal class InspectionCommitService(
    private val gitCli: GitCli
) {
    fun commitAgentChanges(
        projectRoot: File,
        before: Map<String, String>,
        rawMessage: String
    ): CommitOutcome {
        val after = gitCli.captureDirtyFingerprints(projectRoot)
        val changedPaths = (before.keys + after.keys)
            .filter { path -> before[path] != after[path] }
            .sorted()

        if (changedPaths.isEmpty()) {
            println("No file changes detected after agent run. Skip commit.")
            return CommitOutcome.none()
        }

        val addResult = gitCli.run(projectRoot, "add", "-A", "--", *changedPaths.toTypedArray())
        if (addResult.exitCode != 0) {
            println("Failed to stage agent changes.")
            if (addResult.output.isNotBlank()) println(addResult.output.trim())
            return CommitOutcome.none()
        }

        val message = normalizeCommitMessage(rawMessage)
        val commitArgs = mutableListOf("commit", "-m", message, "--")
        commitArgs.addAll(changedPaths)
        val commitResult = gitCli.run(projectRoot, *commitArgs.toTypedArray())
        if (commitResult.exitCode != 0) {
            println("Failed to commit agent changes.")
            if (commitResult.output.isNotBlank()) println(commitResult.output.trim())
            return CommitOutcome.none()
        }

        val headResult = gitCli.run(projectRoot, "rev-parse", "HEAD")
        val commitSha = if (headResult.exitCode == 0) {
            headResult.output.lineSequence().firstOrNull()?.trim()
        } else {
            null
        }
        println("Committed ${changedPaths.size} file(s): $message")
        return CommitOutcome(
            committed = true,
            commitSha = commitSha,
            changedPaths = changedPaths
        )
    }

    private fun normalizeCommitMessage(rawMessage: String): String {
        val patternMatch = Regex("""fix\([^)]+\):\s*.+""")
            .find(rawMessage)
            ?.value
            ?.trim()
        if (!patternMatch.isNullOrBlank()) return patternMatch

        val firstLine = rawMessage
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            ?.replace(Regex("""^`+|`+$"""), "")
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
        if (!firstLine.isNullOrBlank()) return firstLine

        return "fix(inspect): apply inspection fix"
    }
}
