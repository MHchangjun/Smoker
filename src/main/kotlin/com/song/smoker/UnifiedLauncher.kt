package com.song.smoker

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.song.agent.AgentActivityListener
import com.song.agent.tool.EditObserver
import com.song.smoker.detektagent.DetektAgentBridge
import com.song.smoker.detektagent.composeActivityListeners
import com.song.workflow.WorkflowProgress
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.PROJECT)
class UnifiedLauncher(private val project: Project) {

    data class Hooks(
        val log: (String) -> Unit = {},
        val onProgress: (WorkflowProgress) -> Unit = {},
        val activityListener: AgentActivityListener = AgentActivityListener.NONE,
        val onStart: () -> Unit = {},
        val onFinish: (Throwable?) -> Unit = {},
    )

    private val running = AtomicBoolean(false)

    fun isRunning(): Boolean = running.get()

    fun start(hooks: Hooks = Hooks()): Boolean {
        if (!running.compareAndSet(false, true)) return false

        val bridge = project.service<DetektAgentBridge>()
        bridge.onRunStart()
        notifyStateChanged()

        ApplicationManager.getApplication().invokeLater { hooks.onStart() }

        val composedListener = composeActivityListeners(hooks.activityListener, bridge)
        val composedProgress: (WorkflowProgress) -> Unit = { progress ->
            hooks.onProgress(progress)
            bridge.onWorkflowProgress(progress)
        }
        val editObserver = EditObserver { path, removed, added ->
            bridge.recordDiff(path, removed, added)
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Smoker", true) {
            private var failure: Throwable? = null

            override fun run(indicator: ProgressIndicator) {
                try {
                    runBlocking {
                        UnifiedSmokerService(
                            project,
                            hooks.log,
                            composedProgress,
                            composedListener,
                            editObserver,
                        ).run()
                    }
                } catch (e: Throwable) {
                    failure = e
                }
                val err = failure
                ApplicationManager.getApplication().invokeLater { hooks.onFinish(err) }
            }

            override fun onFinished() {
                bridge.onRunFinished(failure)
                running.set(false)
                notifyStateChanged()
            }
        })
        return true
    }

    private fun notifyStateChanged() {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            project.messageBus.syncPublisher(SmokerLauncherTopic.TOPIC).onStateChanged(running.get())
        }
    }
}

fun interface SmokerLauncherListener {
    fun onStateChanged(running: Boolean)
}

object SmokerLauncherTopic {
    @JvmField
    val TOPIC: com.intellij.util.messages.Topic<SmokerLauncherListener> =
        com.intellij.util.messages.Topic.create("Smoker.LauncherState", SmokerLauncherListener::class.java)
}
