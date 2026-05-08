package com.song.smoker

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.song.agent.AgentActivityListener
import com.song.agent.tool.EditObserver
import com.song.detekt.DetektFixService
import com.song.detekt.DetektRunContextFactory
import com.song.detekt.loadDetektConfig
import com.song.di.startAgentKoin
import com.song.git.GitCli
import com.song.git.PullRequestPublishService
import com.song.git.RepositorySyncService
import com.song.inspection.InspectionFixService
import com.song.inspection.InspectionRunContextFactory
import com.song.inspection.InspectionScanService
import com.song.inspection.InspectionSummaryPrinter
import com.song.lint.LintFixService
import com.song.lint.LintRunContextFactory
import com.song.lint.LintScanService
import com.song.workflow.UnifiedBranchService
import com.song.workflow.WorkflowProgress
import com.song.workflow.WorkflowProgressListener
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

            // Phase 1: IDE Inspect (LLM)
            val inspectStart = System.currentTimeMillis()
            runInspectPhase(koin, rootFile)
            logBoth("[inspect] phase end elapsedMs=${System.currentTimeMillis() - inspectStart}")

            // Phase 2: Detekt (LLM)
            val detektStart = System.currentTimeMillis()
            runDetektPhase(koin, rootPath.toAbsolutePath(), rootFile)
            logBoth("[detekt] phase end elapsedMs=${System.currentTimeMillis() - detektStart}")

            // Phase 3: Android Lint (LLM)
            val lintStart = System.currentTimeMillis()
            runLintPhase(koin, rootFile)
            logBoth("[lint] phase end elapsedMs=${System.currentTimeMillis() - lintStart}")

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

    private fun runInspectPhase(koin: org.koin.core.KoinApplication, rootFile: java.io.File) {
        val ctxFactory = koin.koin.get<InspectionRunContextFactory>()
        val scan = koin.koin.get<InspectionScanService>()
        val fix = koin.koin.get<InspectionFixService>()
        val printer = koin.koin.get<InspectionSummaryPrinter>()
        val ctx = ctxFactory.create(rootFile)
        logBoth("[inspect] phase begin ids=${ctx.inspectionIds.joinToString(",")}")
        val findings = scan.scan(ctx).findings
        printer.print(ctx, findings)
        if (findings.isEmpty()) {
            logBoth("[inspect] no findings, skipping LLM phase")
            return
        }
        fix.fixAll(rootFile, findings)
    }

    private fun runDetektPhase(
        koin: org.koin.core.KoinApplication,
        rootPath: java.nio.file.Path,
        rootFile: java.io.File,
    ) {
        val ctxFactory = koin.koin.get<DetektRunContextFactory>()
        val fix = koin.koin.get<DetektFixService>()
        val ctx = ctxFactory.create(rootFile)
        val detektConfig = loadDetektConfig(rootPath)
        if (detektConfig == null) {
            logBoth("[detekt] detekt config not found, skipping LLM phase")
            return
        }
        logBoth("[detekt] phase begin task=${ctx.task}")
        fix.fixAll(detektConfig, ctx, rootFile)
    }

    private fun runLintPhase(koin: org.koin.core.KoinApplication, rootFile: java.io.File) {
        val ctxFactory = koin.koin.get<LintRunContextFactory>()
        val scan = koin.koin.get<LintScanService>()
        val fix = koin.koin.get<LintFixService>()
        val ctx = ctxFactory.create(rootFile)
        logBoth("[lint] running scan (gradle ${ctx.task})")
        val findings = scan.scan(ctx).findings
        logBoth("[lint] scan complete findings=${findings.size}")
        if (findings.isEmpty()) {
            logBoth("[lint] no findings, skipping LLM phase")
            return
        }
        fix.fixAll(ctx, rootFile)
    }

    private fun logBoth(msg: String) {
        println(msg)
        log(msg)
    }
}
