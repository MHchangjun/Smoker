package com.song.agent.tool.di

import aws.smithy.kotlin.runtime.retries.delay.InfiniteTokenBucket.config
import com.song.agent.tool.*
import com.song.lsp.LspClient
import org.koin.core.module.Module
import org.koin.dsl.module
import java.nio.file.Path

fun toolModule(root: Path): Module = module {
    single { ShellTool(ShellTool.Config(workDir = root.toFile())) }
    single { GrepTool(GrepTool.Config(workDir = root.toFile())) }
    single { GlobTool(GlobTool.Config(workDir = root.toFile())) }
    single { ReadFileTool(ReadFileTool.Config(workDir = root.toFile())) }
    single { WriteFileTool(get<LspClient>(), WriteFileTool.Config(workDir = root.toFile())) }
    single { EditTool(get<LspClient>(), EditTool.Config(workDir = root.toFile())) }
    single { LspTool(get<LspClient>()) }
}
