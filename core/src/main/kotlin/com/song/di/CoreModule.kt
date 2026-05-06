package com.song.di

import com.intellij.openapi.project.Project
import com.song.agent.AgentActivityListener
import com.song.agent.CodeSmellAgent
import com.song.agent.EditorSessionManager
import com.song.agent.tool.di.toolModule
import com.song.git.GitCli
import com.song.git.PullRequestPublishService
import com.song.git.RepositorySyncService
import org.koin.core.module.Module
import org.koin.dsl.module
import java.nio.file.Path

fun coreModule(root: Path, project: Project): Module = module {
    includes(toolModule(root))

    single { root }
    single { project }
    single { EditorSessionManager(get()) }

    single {
        CodeSmellAgent(
            root.toAbsolutePath().normalize().toString(),
            get(), get(), get(), get(), get(), get(),
            getOrNull<AgentActivityListener>() ?: AgentActivityListener.NONE,
        )
    }

    single { GitCli() }
    single { RepositorySyncService(get()) }
    single { PullRequestPublishService(get()) }
}
