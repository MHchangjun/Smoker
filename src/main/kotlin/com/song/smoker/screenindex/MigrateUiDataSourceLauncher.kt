package com.song.smoker.screenindex

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.song.di.startAgentKoin
import com.song.screen.datasource.UiDataSourceMigrationService
import com.song.smoker.detektagent.DetektAgentBridge
import org.koin.core.context.GlobalContext
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.PROJECT)
class MigrateUiDataSourceLauncher(private val project: Project) {

    private val running = AtomicBoolean(false)

    fun isRunning(): Boolean = running.get()

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return false

        val basePath = project.basePath ?: run {
            running.set(false)
            return false
        }
        val rootFile = Paths.get(basePath).toAbsolutePath().normalize().toFile()
        val bridge = project.service<DetektAgentBridge>()

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Migrate UI → ViewModel", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Waiting for smart mode"
                DumbService.getInstance(project).waitForSmartMode()
                val koin = startAgentKoin(
                    root = rootFile.toPath(),
                    project = project,
                    activityListener = bridge,
                )
                try {
                    val service = koin.koin.get<UiDataSourceMigrationService>()
                    service.run(rootFile) { msg ->
                        indicator.text = msg
                        println(msg)
                    }
                } catch (t: Throwable) {
                    println("[ui-datasource-migrate] ERROR ${t.javaClass.name}: ${t.message}")
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
