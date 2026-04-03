package com.song.git

import java.io.File

class PullRequestPublishService(
    private val gitCli: GitCli
) {
    fun pushAndCreatePr(projectRoot: File, baseBranch: String = "develop"): Boolean {
        val branchResult = gitCli.run(projectRoot, "rev-parse", "--abbrev-ref", "HEAD")
        if (branchResult.exitCode != 0) {
            println("Failed to detect current branch.")
            if (branchResult.output.isNotBlank()) println(branchResult.output.trim())
            return false
        }
        val currentBranch = branchResult.output.lineSequence().firstOrNull()?.trim().orEmpty()
        if (currentBranch.isBlank() || currentBranch == "HEAD") {
            println("Current branch is invalid. Skip push/PR.")
            return false
        }

        val aheadResult = gitCli.run(projectRoot, "rev-list", "--count", "$baseBranch..$currentBranch")
        if (aheadResult.exitCode == 0) {
            val aheadCount = aheadResult.output.trim().toIntOrNull() ?: 0
            if (aheadCount <= 0) {
                println("No commits ahead of $baseBranch on $currentBranch. Skip push/PR.")
                return false
            }
        }

        val pushResult = gitCli.run(projectRoot, "push", "-u", "origin", currentBranch)
        if (pushResult.exitCode != 0) {
            println("Failed to push branch '$currentBranch'.")
            if (pushResult.output.isNotBlank()) println(pushResult.output.trim())
            return false
        }

        val prCreate = runGh(
            projectRoot,
            "pr", "create",
            "--base", baseBranch,
            "--head", currentBranch,
            "--fill"
        )
        if (prCreate.exitCode == 0) {
            println("PR created successfully.")
            if (prCreate.output.isNotBlank()) println(prCreate.output.trim())
            return true
        }

        if (prCreate.output.contains("already exists", ignoreCase = true)) {
            println("PR already exists for branch '$currentBranch'.")
            return true
        }

        println("Failed to create PR with gh.")
        if (prCreate.output.isNotBlank()) println(prCreate.output.trim())
        return false
    }

    private fun runGh(projectRoot: File, vararg args: String): GitCommandResult {
        return try {
            val cmd = mutableListOf("gh")
            cmd.addAll(args)
            val proc = ProcessBuilder(cmd)
                .directory(projectRoot)
                .redirectErrorStream(true)
                .start()
            val out = proc.inputStream.bufferedReader().use { it.readText() }
            val exit = proc.waitFor()
            GitCommandResult(exit, out)
        } catch (e: Exception) {
            GitCommandResult(-1, "Failed to execute gh: ${e.message}")
        }
    }
}
