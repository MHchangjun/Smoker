package com.song.di

import com.song.agent.CodeSmellAgent
import com.song.agent.TestAgent
import com.song.agent.tool.*
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.module
import java.nio.file.Path

fun agentModule(root: Path): Module = module {
    single { root }

    single {
        ShellTool(
            ShellTool.Config(
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
        GlobTool(
            GlobTool.Config(
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
    single {
        WriteFileTool(
            WriteFileTool.Config(
                workDir = root.toFile()
            )
        )
    }
    single {
        EditTool(
            EditTool.Config(
                workDir = root.toFile()
            )
        )
    }
    single { CodeSmellAgent(get(), get(), get(), get(), get(), get()) }
    single { TestAgent(get(), get(), get(), get(), get(), get()) }
}

fun startAgentKoin(root: Path): KoinApplication {
    return startKoin {
        modules(agentModule(root))
    }
}
