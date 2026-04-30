package com.song.smoker.detektagent.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.song.smoker.SmokerLauncher

class DetektAgentToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = DetektAgentPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.isCloseable = false
        toolWindow.contentManager.addContent(content)
        toolWindow.setTitleActions(listOf(RunSmokerAction()))
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}

private class RunSmokerAction :
    AnAction("Run Smoker", "Trigger one detekt fix cycle", AllIcons.Actions.Execute), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val running = project?.service<SmokerLauncher>()?.isRunning() ?: false
        e.presentation.isEnabled = project != null && !running
        e.presentation.icon = if (running) AllIcons.Process.Step_passive else AllIcons.Actions.Execute
        e.presentation.text = if (running) "Smoker Running…" else "Run Smoker"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        project.service<SmokerLauncher>().start()
    }
}

