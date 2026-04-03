package com.song.di

import com.song.detekt.di.detektModule
import com.song.inspection.di.inspectionModule
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.module
import java.nio.file.Path

fun agentModule(root: Path): Module = module {
    includes(coreModule(root))
    includes(detektModule())
    includes(inspectionModule())
}

fun startAgentKoin(root: Path): KoinApplication {
    return startKoin {
        modules(agentModule(root))
    }
}
