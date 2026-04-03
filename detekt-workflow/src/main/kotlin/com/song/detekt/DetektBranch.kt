package com.song.detekt

import com.song.git.GitCli
import java.io.File
import java.time.LocalDate

class DetektBranchService(
    private val gitCli: GitCli
) {
    fun checkoutOrCreateForToday(projectRoot: File): Boolean {
        val today = LocalDate.now()
        val branchName = "lint/${today.year}-${today.monthValue}-${today.dayOfMonth}"

        println("Preparing git branch: $branchName")
        val create = gitCli.run(projectRoot, "switch", "-c", branchName)
        if (create.exitCode == 0) {
            println("Checked out new branch: $branchName")
            return true
        }

        val useExisting = gitCli.run(projectRoot, "switch", branchName)
        if (useExisting.exitCode == 0) {
            println("Branch already exists. Switched to: $branchName")
            return true
        }

        println("Failed to prepare branch '$branchName'.")
        if (create.output.isNotBlank()) println(create.output.trim())
        if (useExisting.output.isNotBlank()) println(useExisting.output.trim())
        return false
    }
}
