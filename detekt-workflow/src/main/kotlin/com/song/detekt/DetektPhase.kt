package com.song.detekt

import com.song.workflow.WorkflowPhase
import java.io.File

class DetektPhase(
    private val contextFactory: DetektRunContextFactory,
    private val fixService: DetektFixService,
) : WorkflowPhase {
    override val name: String = "detekt"

    override fun run(projectRoot: File, log: (String) -> Unit) {
        val ctx = contextFactory.create(projectRoot)
        val detektConfig = loadDetektConfig(projectRoot.toPath().toAbsolutePath())
        if (detektConfig == null) {
            log("[$name] detekt config not found, skipping LLM phase")
            return
        }
        log("[$name] phase begin task=${ctx.task}")
        fixService.fixAll(detektConfig, ctx, projectRoot)
    }
}
