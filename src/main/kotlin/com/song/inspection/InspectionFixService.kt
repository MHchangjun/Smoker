package com.song.inspection

import com.song.agent.InspectionAgent
import com.song.workflow.CommitOutcome
import com.song.workflow.GitCli
import kotlinx.coroutines.runBlocking
import java.io.File

internal class InspectionFixService(
    private val promptBuilder: InspectionPromptBuilder,
    private val commitService: InspectionCommitService,
    private val gitCli: GitCli,
    private val agent: InspectionAgent
) {
    fun fixOne(
        finding: InspectionFinding,
        projectRoot: File
    ): CommitOutcome {
        if (finding.absolutePath.isNullOrBlank()) {
            println("Selected finding has no absolute path. Skip.")
            return CommitOutcome.none()
        }

        var outcome = CommitOutcome.none()
        runBlocking {
            val before = gitCli.captureDirtyFingerprints(projectRoot)
            val prompt = promptBuilder.build(finding.absolutePath, listOf(finding))
            val rawMessage = agent.start(prompt)
            outcome = commitService.commitAgentChanges(projectRoot, before, rawMessage)
        }
        return outcome
    }
}
