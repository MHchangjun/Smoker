package com.song.di

import com.song.agent.CodeSmellAgent
import com.song.agent.tool.SearchReplaceTool
import com.song.agent.tool.ShellCommandTool
import com.song.agent.tool.TodoTool
import org.koin.core.KoinApplication
import org.koin.core.module.Module
import org.koin.core.context.startKoin
import org.koin.dsl.module
import java.nio.file.Path

fun agentModule(root: Path): Module = module {
    single { root }
    single { ShellCommandTool() }
    single { SearchReplaceTool(get()) }
    single { TodoTool() }
    single { CodeSmellAgent(get(), get(), get()) }
}

fun startAgentKoin(root: Path): KoinApplication {
    return startKoin {
        modules(agentModule(root))
    }
}
