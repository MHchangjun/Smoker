package com.song.workflow

import java.io.File

/**
 * One LLM-driven cleanup phase (IDE Inspect, Detekt, Android Lint, ...).
 *
 * Each phase owns its own scan → summary → fix flow. The orchestrator only
 * sequences phases and provides a logger sink so phase output reaches the UI.
 */
interface WorkflowPhase {
    val name: String
    fun run(projectRoot: File, log: (String) -> Unit)
}
