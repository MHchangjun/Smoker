package com.song.smoker

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.song.agent.AgentActivityListener
import com.song.agent.tool.EditObserver
import com.song.detekt.DetektPhase
import com.song.di.startAgentKoin
import com.song.git.GitCli
import com.song.git.PullRequestPublishService
import com.song.git.RepositorySyncService
import com.song.inspection.InspectionPhase
import com.song.lint.LintPhase
import com.song.workflow.UnifiedBranchService
import com.song.workflow.WorkflowPhase
import com.song.workflow.WorkflowProgress
import com.song.workflow.WorkflowProgressListener
import org.koin.core.KoinApplication
import org.koin.core.context.GlobalContext
import java.nio.file.Paths

/**
 * Single-button workflow:
 *   init → IDE Inspect (LLM) → Detekt (LLM) → Android Lint (LLM)
 *
 * All phases run on one branch named `smoker/<yyyy-MM-dd-HH-mm>` and the LLM agent
 * does the actual fixing — auto-fix proved unsafe for things like design-system
 * tokens, response models populated by reflection, and other library-style symbols.
 * Each fix service commits its own changes via the shared `CommitService`.
 */
class UnifiedSmokerService(
    private val project: Project,
    private val log: (String) -> Unit,
    private val onWorkflowProgress: (WorkflowProgress) -> Unit = {},
    private val activityListener: AgentActivityListener = AgentActivityListener.NONE,
    private val editObserver: EditObserver = EditObserver.NONE,
) {
    suspend fun run() {
        val t0 = System.currentTimeMillis()
        val basePath = project.basePath
            ?: throw IllegalStateException("project.basePath is null — open a real project first")
        val rootPath = Paths.get(basePath).toAbsolutePath().normalize()
        val rootFile = rootPath.toFile()
        onWorkflowProgress(WorkflowProgress())

        logBoth("=== Smoker run starting for project=${project.name} root=${rootPath} ===")
        logBoth("[init] waiting for smart mode (dumb=${DumbService.getInstance(project).isDumb})")
        DumbService.getInstance(project).waitForSmartMode()
        logBoth("[init] smart mode reached")

        logBoth("[init] starting Koin")
        val koin = startAgentKoin(
            rootPath,
            project,
            WorkflowProgressListener { progress -> onWorkflowProgress(progress) },
            activityListener,
            editObserver,
        )
        logBoth("[init] Koin started")
        try {
            val gitCli = koin.koin.get<GitCli>()
            val sync = koin.koin.get<RepositorySyncService>()
            val publish = koin.koin.get<PullRequestPublishService>()

            logBoth("[init] git: pulling develop")
            if (!sync.syncDevelopLatest(rootFile)) {
                logBoth("[init] develop sync failed — aborting")
                return
            }
            logBoth("[init] git: develop synced")

            val branch = UnifiedBranchService(gitCli).createNewBranch(rootFile)
            if (branch == null) {
                logBoth("[init] branch creation failed — aborting")
                return
            }
            logBoth("[init] on branch: $branch")

            for (phase in phases(koin)) {
                val phaseStart = System.currentTimeMillis()
                phase.run(rootFile, ::logBoth)
                logBoth("[${phase.name}] phase end elapsedMs=${System.currentTimeMillis() - phaseStart}")
            }

            logBoth("[publish] pushing branch and creating PR")
            val pushed = publish.pushAndCreatePr(rootFile, baseBranch = "develop")
            logBoth("[publish] result=$pushed")
            logBoth("=== Smoker run done elapsedMs=${System.currentTimeMillis() - t0} ===")
        } finally {
            onWorkflowProgress(WorkflowProgress())
            GlobalContext.stopKoin()
            logBoth("[shutdown] Koin stopped")
        }
    }

    private fun phases(koin: KoinApplication): List<WorkflowPhase> = listOf(
        koin.koin.get<InspectionPhase>(),
        koin.koin.get<DetektPhase>(),
        koin.koin.get<LintPhase>(),
    )

    private fun logBoth(msg: String) {
        println(msg)
        log(msg)
    }
}
