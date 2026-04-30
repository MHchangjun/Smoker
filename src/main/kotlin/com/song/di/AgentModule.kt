package com.song.di

import com.intellij.openapi.project.Project
import com.song.agent.AgentActivityListener
import com.song.agent.tool.EditObserver
import com.song.detekt.DetektProgressListener
import com.song.detekt.di.detektModule
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.module
import java.nio.file.Path

fun agentModule(
    root: Path,
    project: Project,
    progressListener: DetektProgressListener = DetektProgressListener.NONE,
    activityListener: AgentActivityListener = AgentActivityListener.NONE,
    editObserver: EditObserver = EditObserver.NONE,
): Module = module {
    includes(coreModule(root, project))
    includes(detektModule())
    single<DetektProgressListener> { progressListener }
    single<AgentActivityListener> { activityListener }
    single<EditObserver> { editObserver }
}

fun startAgentKoin(
    root: Path,
    project: Project,
    progressListener: DetektProgressListener = DetektProgressListener.NONE,
    activityListener: AgentActivityListener = AgentActivityListener.NONE,
    editObserver: EditObserver = EditObserver.NONE,
): KoinApplication {
    return startKoin {
        modules(agentModule(root, project, progressListener, activityListener, editObserver))
    }
}
