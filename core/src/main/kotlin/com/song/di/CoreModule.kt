package com.song.di

import com.song.agent.CodeSmellAgent
import com.song.agent.tool.di.toolModule
import com.song.git.GitCli
import com.song.git.PullRequestPublishService
import com.song.git.RepositorySyncService
import com.song.lsp.LspClient
import com.song.lsp.LspProcessManager
import org.koin.core.module.Module
import org.koin.dsl.module
import java.nio.file.Path

fun coreModule(root: Path): Module = module {
    includes(toolModule(root))

    single { root }

    single { LspProcessManager(projectRoot = root) }
    single { LspClient(get(), root) }

    single { CodeSmellAgent(root.toAbsolutePath().normalize().toString(), get(), get(), get(), get(), get(), get(), get()) }

    single { GitCli() }
    single { RepositorySyncService(get()) }
    single { PullRequestPublishService(get()) }
}
