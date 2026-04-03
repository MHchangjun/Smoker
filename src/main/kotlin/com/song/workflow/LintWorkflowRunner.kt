package com.song.workflow

import com.song.detekt.DetektConfigContext
import java.io.File

internal class LintWorkflowRunner(
    private val contextFactory: LintRunContextFactory,
    private val repositorySyncService: RepositorySyncService,
    private val scanService: LintScanService,
    private val summaryPrinter: LintSummaryPrinter,
    private val branchService: LintBranchService,
    private val fixService: LintFixService,
    private val buildValidationService: BuildValidationService,
    private val publishService: PullRequestPublishService,
) {
    fun run(projectRoot: File, detektConfig: DetektConfigContext?) {
        val context = contextFactory.create(projectRoot)

        if (!repositorySyncService.syncDevelopLatest(context.projectRoot)) {
            println("Repository sync failed. Skipping lint run.")
            return
        }

        val scan = scanService.scan(context)
        summaryPrinter.print(context, scan.findings)

        if (scan.findings.isEmpty()) {
            println("No findings to fix.")
            return
        }

        if (!branchService.checkoutOrCreateForToday(context.projectRoot)) {
            println("Branch setup failed. Skipping agent execution.")
            return
        }

        val outcomes = fixService.fixAll(detektConfig, scan.findings, context.projectRoot)
        if (outcomes.isEmpty()) {
            println("No commits created. Skip push/PR.")
            return
        }

        val compileTask = ":${context.module}:compilePlayStoreDebugKotlin"
        if (!buildValidationService.verifyCompileOrRollback(context.projectRoot, outcomes, compileTask)) {
            println("Build validation failed. Skip push/PR.")
            return
        }

        publishService.pushAndCreatePr(context.projectRoot, baseBranch = "develop")
    }
}
