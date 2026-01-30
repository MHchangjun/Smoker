package com.song.di

import com.song.agent.CodeSmellAgent
import com.song.agent.TestAgent
import com.song.agent.tool.BashTool
import com.song.agent.tool.GrepTool
import com.song.agent.tool.ReadFileTool
import com.song.agent.tool.SearchReplaceTool
import com.song.agent.tool.StrictEditTool
import com.song.agent.tool.TodoTool
import org.koin.core.KoinApplication
import org.koin.core.module.Module
import org.koin.core.context.startKoin
import org.koin.dsl.module
import java.nio.file.Path

fun agentModule(root: Path): Module = module {
    single { root }

    single {
        BashTool(
            BashTool.Config(
                workDir = root.toFile(),
            )
        )
    }

    single {
        GrepTool(
            GrepTool.Config(
                workDir = root.toFile()
            )
        )
    }

    single {
        ReadFileTool(
            ReadFileTool.Config(
                workDir = root.toFile()
            )
        )
    }
    single { SearchReplaceTool(get()) }
    single { TodoTool() }
    single { CodeSmellAgent(get(), get(), get()) }
    single { TestAgent(get(), get(), get(), get(), get()) }
    single { StrictEditTool(get()) }
}

fun startAgentKoin(root: Path): KoinApplication {
    return startKoin {
        modules(agentModule(root))
    }
}
