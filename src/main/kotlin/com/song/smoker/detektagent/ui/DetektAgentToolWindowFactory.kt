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
import com.song.smoker.InspectionLauncher
import com.song.smoker.LintLauncher
import com.song.smoker.SmokerLauncher

class DetektAgentToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = DetektAgentPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.isCloseable = false
        toolWindow.contentManager.addContent(content)
        toolWindow.setTitleActions(listOf(RunSmokerAction(), RunLintAction(), RunInspectionAction()))
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}

private fun anyWorkflowRunning(project: com.intellij.openapi.project.Project?): Boolean {
    if (project == null) return false
    return project.service<SmokerLauncher>().isRunning()
        || project.service<LintLauncher>().isRunning()
        || project.service<InspectionLauncher>().isRunning()
}

private class RunSmokerAction :
    AnAction("Run Smoker", "Trigger one detekt fix cycle", AllIcons.Actions.Execute), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val detekt = project?.service<SmokerLauncher>()?.isRunning() ?: false
        e.presentation.isEnabled = project != null && !anyWorkflowRunning(project)
        e.presentation.icon = if (detekt) AllIcons.Process.Step_passive else AllIcons.Actions.Execute
        e.presentation.text = if (detekt) "Smoker Running…" else "Run Smoker"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        project.service<SmokerLauncher>().start()
    }
}

private class RunLintAction :
    AnAction("Run Lint", "Trigger one Android Lint fix cycle", AllIcons.General.InspectionsOK), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val lint = project?.service<LintLauncher>()?.isRunning() ?: false
        e.presentation.isEnabled = project != null && !anyWorkflowRunning(project)
        e.presentation.icon = if (lint) AllIcons.Process.Step_passive else AllIcons.General.InspectionsOK
        e.presentation.text = if (lint) "Lint Running…" else "Run Lint"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        project.service<LintLauncher>().start()
    }
}

private class RunInspectionAction :
    AnAction("Run Inspection", "Trigger one IDE inspection fix cycle", AllIcons.Actions.IntentionBulb), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val inspection = project?.service<InspectionLauncher>()?.isRunning() ?: false
        e.presentation.isEnabled = project != null && !anyWorkflowRunning(project)
        e.presentation.icon = if (inspection) AllIcons.Process.Step_passive else AllIcons.Actions.IntentionBulb
        e.presentation.text = if (inspection) "Inspection Running…" else "Run Inspection"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        project.service<InspectionLauncher>().start()
    }
}
