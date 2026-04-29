package com.song.detekt

import com.song.agent.CodeSmellAgent
import com.song.agent.EditorSessionManager
import com.song.git.CommitOutcome
import com.song.git.GitCli
import com.song.sarif.Finding
import kotlinx.coroutines.runBlocking
import java.io.File


class DetektFixService(
    private val promptBuilder: DetektPromptBuilder,
    private val commitService: DetektCommitService,
    private val gitCli: GitCli,
    private val agent: CodeSmellAgent,
    private val scanService: DetektScanService,
    private val editorSessionManager: EditorSessionManager,
    private val progressListener: DetektProgressListener,
) {
    fun fixAll(
        detektConfig: DetektConfigContext?,
        context: DetektRunContext,
        projectRoot: File
    ): List<CommitOutcome> {
        if (detektConfig == null) {
            println("Detekt config not found. Skipping agent execution.")
            return emptyList()
        }

        val outcomes = mutableListOf<CommitOutcome>()
        runBlocking {
            val scan = scanService.scan(context)
            val batch = nextRuleBatch(scan.findings) ?: return@runBlocking

            println("Processing rule ${batch.ruleId} in ${batch.items.size} files ")

            for ((fileIndex, item) in batch.items.withIndex()) {
                progressListener.onRuleProgress(
                    DetektRuleProgress(
                        ruleId = batch.ruleId,
                        totalFilesInRule = batch.items.size,
                        remainingFilesInRule = batch.items.size - fileIndex - 1,
                        currentFileIndex = fileIndex + 1,
                        currentFilePath = item.path,
                        currentFindings = item.findings,
                    )
                )

                println(
                    "  file ${fileIndex + 1}/${batch.items.size}: ${item.path} " +
                            "(${item.findings.size} findings)"
                )

                val before = gitCli.captureDirtyFingerprints(projectRoot)
                val base = promptBuilder.build(item.path, item.findings)
                val editorLease = editorSessionManager.openForAgent(item.path)
                val rawMessage = try {
                    agent.start(base)
                } finally {
                    editorSessionManager.closeForAgent(editorLease)
                }
                val outcome = commitService.commitAgentChanges(projectRoot, before, rawMessage)
                if (outcome.committed) {
                    outcomes += outcome
                }
            }
        }
        return outcomes
    }

    private fun nextRuleBatch(findings: List<Finding>): RuleBatch? {
        val localFindings = findings
            .filter { !it.absolutePath.isNullOrBlank() }
            .filter { !isComposeFile(it.absolutePath!!) }

        val firstRuleId = localFindings.firstOrNull()?.ruleId ?: return null
        val items = localFindings
            .filter { it.ruleId == firstRuleId }
            .groupBy { it.absolutePath!! }
            .map { (path, fileFindings) ->
                RuleWorkItem(path = path, findings = fileFindings)
            }

        return RuleBatch(ruleId = firstRuleId, items = items)
    }

    private fun isComposeFile(path: String): Boolean {
        val file = File(path)
        if (!file.isFile) return false
        return runCatching { file.readText() }
            .getOrNull()
            ?.contains("@Composable")
            ?: false
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
