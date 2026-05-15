package com.song.smoker.screenindex

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.song.agent.SmokerLlmSettings
import com.song.di.startAgentKoin
import com.song.screen.ScreenIndexService
import com.song.smoker.SmokerLlmSettingsDialog
import com.song.smoker.detektagent.DetektAgentBridge
import kotlinx.coroutines.runBlocking
import org.koin.core.context.GlobalContext
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.PROJECT)
class ScreenIndexLauncher(private val project: Project) {

    private val running = AtomicBoolean(false)

    fun isRunning(): Boolean = running.get()

    fun start(): Boolean {
        if (running.get()) return false
        if (!SmokerLlmSettings.getInstance().isConfigured()) {
            if (!ApplicationManager.getApplication().isDispatchThread) return false
            val ok = SmokerLlmSettingsDialog(project).showAndGet()
            if (!ok) return false
        }
        if (!running.compareAndSet(false, true)) return false

        val basePath = project.basePath ?: run {
            running.set(false)
            return false
        }
        val rootFile = Paths.get(basePath).toAbsolutePath().normalize().toFile()
        val bridge = project.service<DetektAgentBridge>()

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Build Screen Index", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Waiting for smart mode"
                DumbService.getInstance(project).waitForSmartMode()
                val koin = startAgentKoin(
                    root = rootFile.toPath(),
                    project = project,
                    activityListener = bridge,
                )
                try {
                    val service = koin.koin.get<ScreenIndexService>()
                    runBlocking {
                        service.build(rootFile) { msg ->
                            indicator.text = msg
                            println(msg)
                        }
                    }
                } catch (t: Throwable) {
                    println("[screen-index] ERROR ${t.javaClass.name}: ${t.message}")
                    t.printStackTrace()
                } finally {
                    GlobalContext.stopKoin()
                }
            }

            override fun onFinished() {
                running.set(false)
            }
        })
        return true
    }
}
