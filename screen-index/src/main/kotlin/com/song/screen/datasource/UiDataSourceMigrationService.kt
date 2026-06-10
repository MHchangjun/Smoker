package com.song.screen.datasource

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.song.agent.EditorSessionManager
import com.song.agent.UiDataSourceMigrationAgent
import com.song.git.CommitOutcome
import com.song.git.GitCli
import com.song.workflow.CommitService
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.Duration
import java.time.Instant

/**
 * Drive the UI → ViewModel migration one violation at a time.
 *
 * Workflow per violation:
 *  1. Build a prompt covering that single violation.
 *  2. Run the agent.
 *  3. Commit the resulting diff via [CommitService] so each violation is a separate commit.
 *
 * The service consumes the scan report at `.smoker/ui-datasource-scan.json` (produced by
 * [UiDataSourceScanService]); it does not re-run the scan.
 */
class UiDataSourceMigrationService(
    private val project: Project,
    private val store: UiDataSourceScanStore,
    private val promptBuilder: UiDataSourceMigrationPromptBuilder,
    private val targetResolver: MigrationTargetResolver,
    private val agent: UiDataSourceMigrationAgent,
    private val editorSessionManager: EditorSessionManager,
    private val commitService: CommitService,
    private val gitCli: GitCli,
) {

    fun run(projectRoot: File, log: (String) -> Unit = {}): List<CommitOutcome> {
        log("[ui-datasource-migrate] phase begin root=${projectRoot.absolutePath}")
        val report = store.load(projectRoot) ?: run {
            log("[ui-datasource-migrate] no scan report at ${store.pathFor(projectRoot).path} — run scan first")
            return emptyList()
        }
        val violations = report.violations
        if (violations.isEmpty()) {
            log("[ui-datasource-migrate] scan report is empty — nothing to migrate")
            return emptyList()
        }

        DumbService.getInstance(project).waitForSmartMode()

        // Keep violations on the same UI class adjacent so reads of the just-edited file
        // are warm; ordering within a class is stable on callerLine.
        val ordered = violations.sortedWith(
            compareBy({ it.callerClassFqn ?: it.callerFile }, { it.callerLine }),
        )

        log(
            "[ui-datasource-migrate] ${ordered.size} violations " +
                "(${report.generatedAtEpochMs} report, schema v${report.schemaVersion})",
        )

        val outcomes = mutableListOf<CommitOutcome>()
        runBlocking {
            for ((index, violation) in ordered.withIndex()) {
                val caller = violation.callerClassFqn ?: violation.callerFile
                val callerFile = violation.callerFile
                val started = Instant.now()
                log(
                    "[ui-datasource-migrate] (${index + 1}/${ordered.size}) $caller " +
                        "line ${violation.callerLine} — leaf ${violation.leaf}",
                )

                val before = gitCli.captureDirtyFingerprints(projectRoot)
                val target = ReadAction.compute<MigrationTarget, RuntimeException> {
                    targetResolver.resolve(violation)
                }
                val prompt = promptBuilder.build(
                    uiClassFqn = violation.callerClassFqn,
                    callerFile = callerFile,
                    violations = listOf(violation),
                    target = target,
                )
                val editorLease = editorSessionManager.openForAgent(callerFile)
                val rawMessage = try {
                    agent.start(prompt)
                } catch (t: Throwable) {
                    log("[ui-datasource-migrate] agent error on $caller: ${t.message}")
                    editorSessionManager.closeForAgent(editorLease)
                    continue
                }
                editorSessionManager.closeForAgent(editorLease)

                val outcome = commitService.commitAgentChanges(projectRoot, before, rawMessage)
                if (outcome.committed) outcomes += outcome
                val elapsedMs = Duration.between(started, Instant.now()).toMillis()
                log(
                    "[ui-datasource-migrate]   committed=${outcome.committed} " +
                        "sha=${outcome.commitSha ?: "-"} ${elapsedMs}ms",
                )
            }
        }
        log("[ui-datasource-migrate] done — ${outcomes.size}/${ordered.size} commits")
        return outcomes
    }
}
