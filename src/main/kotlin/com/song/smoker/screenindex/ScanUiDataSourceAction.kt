package com.song.smoker.screenindex

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware

class ScanUiDataSourceAction :
    AnAction(
        "Scan UI → Data Source",
        "Find UI classes (Activity / Fragment / View / Adapter / Composable) directly referencing data-source leaves",
        AllIcons.General.InspectionsEye,
    ),
    DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val running = project?.service<ScanUiDataSourceLauncher>()?.isRunning() == true
        e.presentation.isEnabled = project != null && !running
        e.presentation.text = if (running) "Scanning UI → Data Source…" else "Scan UI → Data Source"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        project.service<ScanUiDataSourceLauncher>().start()
    }
}
