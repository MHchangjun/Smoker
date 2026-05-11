package com.song.smoker.daemon

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class SmokerHttpListenerActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (System.getenv("SMOKER_HTTP_LISTEN") != "1") return
        project.service<SmokerHttpListener>().start()
    }
}
