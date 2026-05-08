package com.song.detekt.di

import com.song.detekt.*
import com.song.workflow.BranchService
import com.song.workflow.BuildValidationService
import com.song.workflow.CommitService
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

fun detektModule(): Module = module {
    single { DetektRunContextFactory() }
    single { DetektScanService() }
    single { DetektSummaryPrinter() }
    single { DetektPromptBuilder() }
    single(named("detekt")) { CommitService(get(), fallbackMessage = "fix(detekt): apply lint fixes") }
    single(named("detekt")) { BranchService(get(), branchPrefix = "lint") }
    single { BuildValidationService(get()) }
    single {
        DetektFixService(
            get(),                       // DetektPromptBuilder
            get(named("detekt")),        // CommitService
            get(),                       // GitCli
            get(),                       // CodeSmellAgent
            get(),                       // DetektScanService
            get(),                       // EditorSessionManager
            get(),                       // WorkflowProgressListener
        )
    }
    single {
        DetektWorkflowRunner(
            get(),                       // DetektRunContextFactory
            get(),                       // RepositorySyncService
            get(),                       // DetektScanService
            get(),                       // DetektSummaryPrinter
            get(named("detekt")),        // BranchService
            get(),                       // DetektFixService
            get(),                       // BuildValidationService
            get(),                       // PullRequestPublishService
        )
    }
}
