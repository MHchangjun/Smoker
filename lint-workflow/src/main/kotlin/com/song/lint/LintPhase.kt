package com.song.lint

import com.song.workflow.WorkflowPhase
import java.io.File

class LintPhase(
    private val contextFactory: LintRunContextFactory,
    private val scanService: LintScanService,
    private val fixService: LintFixService,
) : WorkflowPhase {
    override val name: String = "lint"

    override fun run(projectRoot: File, log: (String) -> Unit) {
        val ctx = contextFactory.create(projectRoot)
        log("[$name] running scan (gradle ${ctx.task})")
        val findings = scanService.scan(ctx).findings
        log("[$name] scan complete findings=${findings.size}")
        if (findings.isEmpty()) {
            log("[$name] no findings, skipping LLM phase")
            return
        }
        fixService.fixAll(ctx, projectRoot)
    }
}
