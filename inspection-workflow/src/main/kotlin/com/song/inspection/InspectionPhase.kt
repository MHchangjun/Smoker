package com.song.inspection

import com.song.workflow.WorkflowPhase
import java.io.File

class InspectionPhase(
    private val contextFactory: InspectionRunContextFactory,
    private val scanService: InspectionScanService,
    private val summaryPrinter: InspectionSummaryPrinter,
    private val fixService: InspectionFixService,
) : WorkflowPhase {
    override val name: String = "code inspect"

    override fun run(projectRoot: File, log: (String) -> Unit) {
        val ctx = contextFactory.create(projectRoot)
        log("[$name] phase begin ids=${ctx.inspectionIds.joinToString(",")}")
        val findings = scanService.scan(ctx).findings
        summaryPrinter.print(ctx, findings)
        if (findings.isEmpty()) {
            log("[$name] no findings, skipping LLM phase")
            return
        }
        val groupedByRule = findings.groupBy { it.ruleId }
        for ((ruleId, ruleFindings) in groupedByRule) {
            log("[$name] rule=$ruleId findings=${ruleFindings.size}")
            fixService.fixAll(projectRoot, ruleFindings)
        }
    }
}
