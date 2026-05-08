package com.song.inspection

import com.song.agent.CodeSmellAgent
import com.song.agent.EditorSessionManager
import com.song.git.CommitOutcome
import com.song.git.GitCli
import com.song.sarif.Finding
import com.song.workflow.CommitService
import com.song.workflow.PreviousFileOutcome
import com.song.workflow.UpcomingTask
import com.song.workflow.WorkflowProgress
import com.song.workflow.WorkflowProgressListener
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.Duration
import java.time.Instant

class InspectionFixService(
    private val promptBuilder: InspectionPromptBuilder,
    private val commitService: CommitService,
    private val gitCli: GitCli,
    private val agent: CodeSmellAgent,
    private val editorSessionManager: EditorSessionManager,
    private val progressListener: WorkflowProgressListener,
) {
    fun fixAll(
        projectRoot: File,
        findings: List<Finding>,
    ): List<CommitOutcome> {
        val outcomes = mutableListOf<CommitOutcome>()
        runBlocking {
            val batch = nextRuleBatch(findings) ?: return@runBlocking

            println("Processing rule ${batch.ruleId} in ${batch.items.size} files ")

            var lastOutcome: PreviousFileOutcome? = null
            for ((fileIndex, item) in batch.items.withIndex()) {
                val upcoming = batch.items
                    .drop(fileIndex + 1)
                    .map { up ->
                        UpcomingTask(
                            ruleId = batch.ruleId,
                            filePath = up.path,
                            findingCount = up.findings.size,
                        )
                    }
                progressListener.onWorkflowProgress(
                    WorkflowProgress(
                        ruleId = batch.ruleId,
                        totalFilesInRule = batch.items.size,
                        remainingFilesInRule = batch.items.size - fileIndex - 1,
                        currentFileIndex = fileIndex + 1,
                        currentFilePath = item.path,
                        currentFindings = item.findings,
                        upcoming = upcoming,
                        lastOutcome = lastOutcome,
                    )
                )

                println(
                    "  file ${fileIndex + 1}/${batch.items.size}: ${item.path} " +
                        "(${item.findings.size} findings)"
                )

                val before = gitCli.captureDirtyFingerprints(projectRoot)
                val base = promptBuilder.build(item.path, item.findings)
                val editorLease = editorSessionManager.openForAgent(item.path)
                val fileStart = Instant.now()
                val rawMessage = try {
                    agent.start(base)
                } finally {
                    editorSessionManager.closeForAgent(editorLease)
                }
                val outcome = commitService.commitAgentChanges(projectRoot, before, rawMessage)
                if (outcome.committed) {
                    outcomes += outcome
                }
                lastOutcome = PreviousFileOutcome(
                    ruleId = batch.ruleId,
                    filePath = item.path,
                    committed = outcome.committed,
                    commitSha = outcome.commitSha,
                    rawMessage = rawMessage,
                    durationMs = Duration.between(fileStart, Instant.now()).toMillis(),
                )
            }
            if (lastOutcome != null) {
                progressListener.onWorkflowProgress(
                    WorkflowProgress(
                        ruleId = batch.ruleId,
                        totalFilesInRule = batch.items.size,
                        remainingFilesInRule = 0,
                        currentFileIndex = batch.items.size,
                        currentFilePath = null,
                        currentFindings = emptyList(),
                        upcoming = emptyList(),
                        lastOutcome = lastOutcome,
                    )
                )
            }
        }
        return outcomes
    }

    private fun nextRuleBatch(findings: List<Finding>): RuleBatch? {
        val localFindings = findings.filter { !it.absolutePath.isNullOrBlank() }

        val firstRuleId = localFindings.firstOrNull()?.ruleId ?: return null
        val items = localFindings
            .filter { it.ruleId == firstRuleId }
            .groupBy { it.absolutePath!! }
            .map { (path, fileFindings) ->
                RuleWorkItem(path = path, findings = fileFindings)
            }

        return RuleBatch(ruleId = firstRuleId, items = items)
    }

    private data class RuleBatch(
        val ruleId: String,
        val items: List<RuleWorkItem>,
    )

    private data class RuleWorkItem(
        val path: String,
        val findings: List<Finding>,
    )
}
