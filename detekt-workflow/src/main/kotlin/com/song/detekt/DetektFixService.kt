package com.song.detekt

import com.song.agent.CodeSmellAgent
import com.song.git.CommitOutcome
import com.song.git.GitCli
import com.song.sarif.Finding
import kotlinx.coroutines.runBlocking
import java.io.File

private const val MAX_FINDINGS_TO_FIX = 3

class DetektFixService(
    private val promptBuilder: DetektPromptBuilder,
    private val commitService: DetektCommitService,
    private val gitCli: GitCli,
    private val agent: CodeSmellAgent
) {
    fun fixAll(
        detektConfig: DetektConfigContext?,
        findings: List<Finding>,
        projectRoot: File
    ): List<CommitOutcome> {
        if (detektConfig == null) {
            println("Detekt config not found. Skipping agent execution.")
            return emptyList()
        }

        if (findings.isEmpty()) {
            println("No findings to fix.")
            return emptyList()
        }

        val fixableFindings = findings
            .filter { !it.absolutePath.isNullOrBlank() }
        if (fixableFindings.isEmpty()) {
            println("No fixable findings with absolute paths.")
            return emptyList()
        }

        val findingsByFile = fixableFindings
            .groupBy { it.absolutePath!! }

        val outcomes = mutableListOf<CommitOutcome>()
        runBlocking {
            findingsByFile.entries.take(MAX_FINDINGS_TO_FIX).forEach { (path, fileFindings) ->
                val before = gitCli.captureDirtyFingerprints(projectRoot)
                val prompt = promptBuilder.build(path, fileFindings, detektConfig)
                val rawMessage = agent.start(prompt)
                val outcome = commitService.commitAgentChanges(projectRoot, before, rawMessage)
                if (outcome.committed) {
                    outcomes += outcome
                }
            }
        }
        return outcomes
    }
}
