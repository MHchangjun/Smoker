package com.song.smoker

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.song.agent.AgentActivityListener
import com.song.agent.tool.EditObserver
import com.song.detekt.DetektProgressListener
import com.song.detekt.DetektRuleProgress
import com.song.detekt.DetektWorkflowRunner
import com.song.detekt.loadDetektConfig
import com.song.di.startAgentKoin
import org.koin.core.context.GlobalContext
import java.nio.file.Paths

// Workflow entry callable from the Smoker tool window. The IDE owns the Project
// lifecycle (open/close, AGP sync, indexing) so this just waits for smart mode
// as a safety net and runs the existing DetektWorkflowRunner.
class SmokerService(
    private val project: Project,
    private val log: (String) -> Unit,
    private val onRuleProgress: (DetektRuleProgress) -> Unit = {},
    private val activityListener: AgentActivityListener = AgentActivityListener.NONE,
    private val editObserver: EditObserver = EditObserver.NONE,
) {
    suspend fun run() {
        val basePath = project.basePath
            ?: throw IllegalStateException("project.basePath is null — open a real project first")
        val rootPath = Paths.get(basePath).toAbsolutePath().normalize()
        onRuleProgress(DetektRuleProgress())

        log("Starting Smoker for ${project.name}")
        log("Waiting for smart mode...")
        DumbService.getInstance(project).waitForSmartMode()

        log("Initializing agent (Koin)...")
        val koin = startAgentKoin(
            rootPath,
            project,
            DetektProgressListener { progress -> onRuleProgress(progress) },
            activityListener,
            editObserver,
        )
        try {
            log("Running detekt workflow...")
            val detektConfig = loadDetektConfig(rootPath)
            val runner = koin.koin.get<DetektWorkflowRunner>()
            runner.run(rootPath.toFile(), detektConfig)
            log("Done")
        } finally {
            onRuleProgress(DetektRuleProgress())
            GlobalContext.stopKoin()
        }
    }
}
