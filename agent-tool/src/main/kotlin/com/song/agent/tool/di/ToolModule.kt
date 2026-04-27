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
    single { WriteFileTool(WriteFileTool.Config(workDir = root.toFile(), lspClient = get<LspClient>())) }
    single { EditTool(EditTool.Config(workDir = root.toFile(), lspClient = get<LspClient>())) }
    single { LspTool(get<LspClient>()) }
}
