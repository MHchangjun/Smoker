package com.song.workflow

import java.io.File

internal class RepositorySyncService(
    private val gitCli: GitCli
) {
    fun syncDevelopLatest(projectRoot: File): Boolean {
        println("Syncing repository with remote develop branch...")

        val switchResult = gitCli.run(projectRoot, "switch", "develop")
        if (switchResult.exitCode != 0) {
            val trackResult = gitCli.run(projectRoot, "switch", "-c", "develop", "--track", "origin/develop")
            if (trackResult.exitCode != 0) {
                println("Failed to switch to develop branch.")
                if (switchResult.output.isNotBlank()) println(switchResult.output.trim())
                if (trackResult.output.isNotBlank()) println(trackResult.output.trim())
                return false
            }
        }

        val pullResult = gitCli.run(projectRoot, "pull", "--ff-only", "origin", "develop")
        if (pullResult.exitCode != 0) {
            println("Failed to pull latest origin/develop.")
            if (pullResult.output.isNotBlank()) println(pullResult.output.trim())
            return false
        }

        println("Repository is up to date on develop.")
        return true
    }
}
