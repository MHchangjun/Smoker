package com.song.inspection

import com.song.workflow.PullRequestPublishService
import com.song.workflow.RepositorySyncService

internal class InspectionWorkflowRunner(
    private val repositorySyncService: RepositorySyncService,
    private val scanService: InspectionScanService,
    private val prioritizer: InspectionPrioritizer,
    private val branchService: InspectionBranchService,
    private val fixService: InspectionFixService,
    private val publishService: PullRequestPublishService,
) {
    fun run(options: InspectionFixOptions) {
        if (!repositorySyncService.syncDevelopLatest(options.projectRoot)) {
            println("Repository sync failed. Skipping inspection run.")
            return
        }

        val scan = scanService.scan(options)
        println("Parsed ${scan.findings.size} inspection finding(s).")
        println("Inspection result XML files: ${scan.resultXmlCount}")
        scan.warnings.forEach { println("WARNING: $it") }

        if (!scan.trusted) {
            error("Inspection run is untrusted. Failing instead of treating it as a clean nightly run.")
        }

        val candidates = prioritizer.prioritize(scan.findings)
            .take(options.maxFindings)

        if (candidates.isEmpty()) {
            println("No findings to fix.")
            return
        }

        if (!branchService.checkoutOrCreateForToday(options.projectRoot)) {
            println("Branch setup failed. Skipping agent execution.")
            return
        }

        val outcomes = mutableListOf<com.song.workflow.CommitOutcome>()
        candidates.forEach { selected ->
            println(
                "Selected finding: ${selected.ruleId} ${selected.absolutePath ?: selected.uri ?: "unknown"}:${selected.startLine ?: "?"}"
            )

            val outcome = fixService.fixOne(selected, options.projectRoot)
            if (outcome.committed) {
                outcomes += outcome
            } else {
                println("Selected finding produced no commit. Trying the next prioritized finding.")
            }
        }

        if (outcomes.isEmpty()) {
            println("No commits created. Skip push/PR.")
            return
        }

        publishService.pushAndCreatePr(options.projectRoot, baseBranch = "develop")
    }
}
