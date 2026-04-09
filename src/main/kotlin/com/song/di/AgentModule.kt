package com.song.di

import com.song.detekt.di.detektModule
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.module
import java.nio.file.Path

fun agentModule(root: Path): Module = module {
    includes(coreModule(root))
    includes(detektModule())
}

fun startAgentKoin(root: Path): KoinApplication {
    return startKoin {
        modules(agentModule(root))
    }
}
