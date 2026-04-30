package com.song.agent.tool.di

import com.intellij.openapi.project.Project
import com.song.agent.tool.*
import org.koin.core.module.Module
import org.koin.dsl.module
import java.nio.file.Path

fun toolModule(root: Path): Module = module {
    single { ShellTool(ShellTool.Config(workDir = root.toFile())) }
    single { GrepTool(GrepTool.Config(workDir = root.toFile())) }
    single { GlobTool(GlobTool.Config(workDir = root.toFile())) }
    single { ReadFileTool(ReadFileTool.Config(workDir = root.toFile())) }
    single {
        WriteFileTool(
            get<Project>(),
            WriteFileTool.Config(workDir = root.toFile()),
            getOrNull<EditObserver>() ?: EditObserver.NONE,
        )
    }
    single {
        EditTool(
            get<Project>(),
            EditTool.Config(workDir = root.toFile()),
            getOrNull<EditObserver>() ?: EditObserver.NONE,
        )
    }
    single { LspTool(get<Project>()) }
}
