package com.song.di

import com.intellij.openapi.project.Project
import com.song.detekt.di.detektModule
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.module
import java.nio.file.Path

fun agentModule(root: Path, project: Project): Module = module {
    includes(coreModule(root, project))
    includes(detektModule())
}

fun startAgentKoin(root: Path, project: Project): KoinApplication {
    return startKoin {
        modules(agentModule(root, project))
    }
}
