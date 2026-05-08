package com.song.smoker

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.song.agent.AgentActivityListener
import com.song.agent.tool.EditObserver
import com.song.di.startAgentKoin
import com.song.lint.LintWorkflowRunner
import com.song.workflow.WorkflowProgress
import com.song.workflow.WorkflowProgressListener
import org.koin.core.context.GlobalContext
import java.nio.file.Paths

// Lint workflow entry. Mirrors SmokerService but runs the LintWorkflowRunner.
class LintSmokerService(
    private val project: Project,
    private val log: (String) -> Unit,
    private val onWorkflowProgress: (WorkflowProgress) -> Unit = {},
    private val activityListener: AgentActivityListener = AgentActivityListener.NONE,
    private val editObserver: EditObserver = EditObserver.NONE,
) {
    suspend fun run() {
        val basePath = project.basePath
            ?: throw IllegalStateException("project.basePath is null — open a real project first")
        val rootPath = Paths.get(basePath).toAbsolutePath().normalize()
        onWorkflowProgress(WorkflowProgress())

        log("Starting Smoker (lint) for ${project.name}")
        log("Waiting for smart mode...")
        DumbService.getInstance(project).waitForSmartMode()

        log("Initializing agent (Koin)...")
        val koin = startAgentKoin(
            rootPath,
            project,
            WorkflowProgressListener { progress -> onWorkflowProgress(progress) },
            activityListener,
            editObserver,
        )
        try {
            log("Running android lint workflow...")
            val runner = koin.koin.get<LintWorkflowRunner>()
            runner.run(rootPath.toFile())
            log("Done")
        } finally {
            onWorkflowProgress(WorkflowProgress())
            GlobalContext.stopKoin()
        }
    }
}
