package com.song.workflow

import com.song.git.GitCli
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class UnifiedBranchService(
    private val gitCli: GitCli,
) {
    fun createNewBranch(projectRoot: File): String? {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm"))
        val branchName = "smoker/$stamp"

        println("Creating unified branch: $branchName")
        val result = gitCli.run(projectRoot, "switch", "-c", branchName)
        if (result.exitCode == 0) {
            println("Checked out new branch: $branchName")
            return branchName
        }

        println("Failed to create branch '$branchName'.")
        if (result.output.isNotBlank()) println(result.output.trim())
        return null
    }
}
