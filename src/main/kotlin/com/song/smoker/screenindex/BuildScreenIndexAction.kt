package com.song.smoker.screenindex

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware

class BuildScreenIndexAction :
    AnAction(
        "Build Screen Index",
        "Scan project for user-perceived screens and write .smoker/screen-index.json",
        AllIcons.Actions.ListFiles,
    ),
    DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val running = project?.service<ScreenIndexLauncher>()?.isRunning() == true
        e.presentation.isEnabled = project != null && !running
        e.presentation.text = if (running) "Building Screen Index…" else "Build Screen Index"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        project.service<ScreenIndexLauncher>().start()
    }
}
