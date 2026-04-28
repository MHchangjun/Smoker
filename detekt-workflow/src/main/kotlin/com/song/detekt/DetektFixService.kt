package com.song.detekt

import com.song.agent.CodeSmellAgent
import com.song.agent.EditorSessionManager
import com.song.git.CommitOutcome
import com.song.git.GitCli
import kotlinx.coroutines.runBlocking
import java.io.File

private const val MAX_ITERATIONS = 30

private val LOCAL_RULE_PREFIXES = listOf(
    "detekt.complexity.",
    "detekt.empty-blocks.",
    "detekt.exceptions.",
    "detekt.performance.",
    "detekt.potential-bugs.",
)

private val LOCAL_RULES = setOf(
    "detekt.coroutines.GlobalCoroutineUsage",
    "detekt.coroutines.RedundantSuspendModifier",
    "detekt.coroutines.SleepInsteadOfDelay",
    "detekt.coroutines.SuspendFunSwallowedCancellation",
    "detekt.coroutines.SuspendFunWithFlowReturnType",
    "detekt.style.MaxLineLength",
    "detekt.style.NewLineAtEndOfFile",
    "detekt.style.SpacingBetweenPackageAndImports",
    "detekt.naming.NoNameShadowing",
    "detekt.naming.VariableNaming",
)

class DetektFixService(
    private val promptBuilder: DetektPromptBuilder,
    private val commitService: DetektCommitService,
    private val gitCli: GitCli,
    private val agent: CodeSmellAgent,
    private val scanService: DetektScanService,
    private val editorSessionManager: EditorSessionManager,
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
            for (iteration in 0 until MAX_ITERATIONS) {
                val scan = scanService.scan(context)
                val localFindings = scan.findings
                    .filter { !it.absolutePath.isNullOrBlank() }
                    .filter { isLocalRule(it.ruleId) }
                    .groupBy { it.absolutePath!! }
                    .mapNotNull { (path, fileFindings) ->
                        val first = fileFindings.firstOrNull() ?: return@mapNotNull null
                        path to first
                    }

                if (localFindings.isEmpty()) {
                    println("No more local findings. Stopping after $iteration iterations.")
                    break
                }

                for ((path, finding) in localFindings) {
                    val before = gitCli.captureDirtyFingerprints(projectRoot)
                    val base = promptBuilder.build(path, listOf(finding))
                    val editorLease = editorSessionManager.openForAgent(path)
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
        }
        return outcomes
    }

    private fun isLocalRule(ruleId: String): Boolean =
        LOCAL_RULE_PREFIXES.any { ruleId.startsWith(it, ignoreCase = true) }
                || LOCAL_RULES.any { ruleId.equals(it, ignoreCase = true) }
}
