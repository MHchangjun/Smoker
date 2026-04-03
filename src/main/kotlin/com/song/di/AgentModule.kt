package com.song.di

import com.song.agent.CodeSmellAgent
import com.song.agent.InspectionAgent
import com.song.agent.TestAgent
import com.song.agent.tool.*
import com.song.inspection.*
import com.song.workflow.*
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
    single { CodeSmellAgent(root.toAbsolutePath().normalize().toString(), get(), get(), get(), get(), get(), get()) }
    single { InspectionAgent(root.toAbsolutePath().normalize().toString(), get(), get(), get(), get(), get(), get()) }
    single { TestAgent(root.toAbsolutePath().normalize().toString(), get(), get(), get(), get(), get()) }

    single { GitCli() }
    single { LintRunContextFactory() }
    single { LintScanService() }
    single { LintSummaryPrinter() }
    single { LintPromptBuilder() }
    single { LintCommitService(get()) }
    single { RepositorySyncService(get()) }
    single { LintBranchService(get()) }
    single { LintFixService(get(), get(), get(), get()) }
    single { BuildValidationService(get()) }
    single { PullRequestPublishService(get()) }
    single { LintWorkflowRunner(get(), get(), get(), get(), get(), get(), get(), get()) }

    single { JetBrainsInspectRunner() }
    single { JetBrainsInspectionReportParser() }
    single { InspectionPromptBuilder() }
    single { InspectionCommitService(get()) }
    single { InspectionBranchService(get()) }
    single { InspectionScanService(get(), get()) }
    single { InspectionFixService(get(), get(), get(), get()) }
    single { InspectionWorkflowRunner(get(), get(), get(), get(), get()) }
}

fun startAgentKoin(root: Path): KoinApplication {
    return startKoin {
        modules(agentModule(root))
    }
}
