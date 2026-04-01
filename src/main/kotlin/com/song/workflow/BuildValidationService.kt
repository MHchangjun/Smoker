package com.song.workflow

import com.song.detekt.runGradleTask
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

private const val COMPILE_TIMEOUT_MINUTES = 30L
private const val FAILURE_LINE_LIMIT = 120
private const val TAIL_LINE_LIMIT = 80

internal class BuildValidationService(
    private val gitCli: GitCli
) {
    fun verifyCompileOrRollback(
        projectRoot: File,
        outcomes: List<CommitOutcome>,
        compileTask: String
    ): Boolean {
        val compile = runGradleTask(projectRoot, compileTask, timeoutMinutes = COMPILE_TIMEOUT_MINUTES)
        if (compile.exitCode == 0) {
            println("Compile check passed.")
            return true
        }

        println("Compile failed. Rolling back files related to compile errors.")
        printCompileFailureDiagnostics(compile.output)
        val errorFiles = extractErrorFiles(compile.output, projectRoot)
        if (errorFiles.isNotEmpty()) {
            println("Detected compile-error files (${errorFiles.size}): ${errorFiles.joinToString(", ")}")
        }
        val rollbackPlan = buildRollbackPlan(outcomes, errorFiles)
        if (rollbackPlan.isEmpty()) {
            println("No rollback candidates matched compile errors.")
            return false
        }

        rollbackPlan.forEach { (commitSha, targets) ->
            val restored = restoreFilesFromParentCommit(projectRoot, commitSha, targets)
            if (!restored) return false
            val committed = commitRollback(projectRoot, commitSha, targets)
            if (!committed) return false
        }

        val recheck = runGradleTask(projectRoot, compileTask, timeoutMinutes = COMPILE_TIMEOUT_MINUTES)
        if (recheck.exitCode == 0) {
            println("Compile check passed after rollback.")
            return true
        }

        println("Compile still failing after rollback.")
        printCompileFailureDiagnostics(recheck.output)
        val tail = recheck.output.lines().takeLast(40).joinToString("\n")
        if (tail.isNotBlank()) println(tail)
        return false
    }

    private fun printCompileFailureDiagnostics(output: String) {
        val interesting = output.lines().filter { line ->
            line.startsWith("e: ") ||
                line.contains("Execution failed for task") ||
                line.startsWith("FAILURE:") ||
                line.startsWith("* What went wrong:") ||
                line.contains("BUILD FAILED") ||
                (line.startsWith("> Task ") && line.contains("FAILED"))
        }

        if (interesting.isNotEmpty()) {
            println("---- Compile failure summary ----")
            interesting.take(FAILURE_LINE_LIMIT).forEach(::println)
            if (interesting.size > FAILURE_LINE_LIMIT) {
                println("... (${interesting.size - FAILURE_LINE_LIMIT} more failure lines omitted)")
            }
        }

        val tail = output.lines().takeLast(TAIL_LINE_LIMIT)
        if (tail.isNotEmpty()) {
            println("---- Compile output tail ----")
            tail.forEach(::println)
        }
    }

    private fun buildRollbackPlan(
        outcomes: List<CommitOutcome>,
        errorFiles: Set<String>
    ): Map<String, List<String>> {
        val candidates = outcomes
            .asReversed()
            .filter { it.committed && !it.commitSha.isNullOrBlank() }

        val grouped = linkedMapOf<String, MutableList<String>>()
        candidates.forEach { outcome ->
            val sha = outcome.commitSha ?: return@forEach
            val targets = if (errorFiles.isEmpty()) {
                outcome.changedPaths
            } else {
                outcome.changedPaths.filter { it in errorFiles }
            }
            if (targets.isEmpty()) return@forEach
            grouped.getOrPut(sha) { mutableListOf() }.addAll(targets)
        }
        return grouped.mapValues { (_, paths) -> paths.distinct() }
    }

    private fun restoreFilesFromParentCommit(
        projectRoot: File,
        commitSha: String,
        targets: List<String>
    ): Boolean {
        val restore = gitCli.run(
            projectRoot,
            "restore",
            "--source=$commitSha^",
            "--staged",
            "--worktree",
            "--",
            *targets.toTypedArray()
        )
        if (restore.exitCode != 0) {
            println("Failed to restore files from $commitSha.")
            if (restore.output.isNotBlank()) println(restore.output.trim())
            return false
        }
        return true
    }

    private fun commitRollback(
        projectRoot: File,
        commitSha: String,
        targets: List<String>
    ): Boolean {
        val message = "revert(build): rollback failing files from ${commitSha.take(8)}"
        val commit = gitCli.run(
            projectRoot,
            "commit",
            "-m",
            message,
            "--",
            *targets.toTypedArray()
        )
        if (commit.exitCode != 0) {
            println("Failed to create rollback commit for $commitSha.")
            if (commit.output.isNotBlank()) println(commit.output.trim())
            return false
        }

        println("Rolled back ${targets.size} file(s) from ${commitSha.take(8)}.")
        return true
    }

    private fun extractErrorFiles(buildOutput: String, projectRoot: File): Set<String> {
        val projectPath = projectRoot.absoluteFile.normalize().path.replace("\\", "/")
        val files = linkedSetOf<String>()
        val fileUriPattern = Regex("""file:///([^\s:]+?\.(?:kt|kts|java|xml|gradle(?:\.kts)?))""")
        val linePattern = Regex("""([A-Za-z0-9_./\\-]+?\.(?:kt|kts|java|xml|gradle(?:\.kts)?)):\d+""")

        buildOutput.lineSequence().forEach { line ->
            fileUriPattern.findAll(line).forEach { match ->
                normalizeBuildPath(match.groupValues[1], projectPath)?.let(files::add)
            }
            linePattern.findAll(line).forEach { match ->
                normalizeBuildPath(match.groupValues[1], projectPath)?.let(files::add)
            }
        }
        return files
    }

    private fun normalizeBuildPath(rawPath: String, projectPath: String): String? {
        val decoded = runCatching { URLDecoder.decode(rawPath, StandardCharsets.UTF_8.name()) }.getOrElse { rawPath }
        val normalized = decoded.replace("\\", "/").removePrefix("./")
        return when {
            normalized.startsWith("/") && normalized.startsWith("$projectPath/") ->
                normalized.removePrefix("$projectPath/")
            normalized.startsWith("/") -> null
            normalized.isBlank() -> null
            else -> normalized
        }
    }
}
