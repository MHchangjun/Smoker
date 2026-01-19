package com.song.di

import com.song.agent.tool.ApplyPatchTool
import com.song.agent.CodeSmellAgent
import com.song.agent.tool.ShellCommandTool
import org.koin.core.KoinApplication
import org.koin.core.module.Module
import org.koin.core.context.startKoin
import org.koin.dsl.module
import java.nio.file.Path

fun agentModule(root: Path): Module = module {
    single { root }
    single { ShellCommandTool() }
    single { ApplyPatchTool(get()) }
    single { CodeSmellAgent(get(), get()) }
}

fun startAgentKoin(root: Path): KoinApplication {
    return startKoin {
        modules(agentModule(root))
    }
}
