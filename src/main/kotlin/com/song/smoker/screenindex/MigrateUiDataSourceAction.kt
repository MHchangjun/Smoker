package com.song.smoker.screenindex

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware

class MigrateUiDataSourceAction :
    AnAction(
        "Migrate UI → ViewModel",
        "Migrate every leaf access reported in .smoker/ui-datasource-scan.json out of UI classes into ViewModel + Repository",
        AllIcons.Actions.Refresh,
    ),
    DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val running = project?.service<MigrateUiDataSourceLauncher>()?.isRunning() == true
        e.presentation.isEnabled = project != null && !running
        e.presentation.text = if (running) "Migrating UI → ViewModel…" else "Migrate UI → ViewModel"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        project.service<MigrateUiDataSourceLauncher>().start()
    }
}
