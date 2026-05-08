package com.song.lint.di

import com.song.lint.LintFixService
import com.song.lint.LintPromptBuilder
import com.song.lint.LintRunContextFactory
import com.song.lint.LintScanService
import com.song.lint.LintSummaryPrinter
import com.song.lint.LintWorkflowRunner
import com.song.lint.LintXmlParser
import com.song.workflow.BranchService
import com.song.workflow.CommitService
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

fun lintModule(): Module = module {
    single { LintRunContextFactory() }
    single { LintXmlParser() }
    single { LintScanService(get()) }
    single { LintSummaryPrinter() }
    single { LintPromptBuilder() }
    single(named("lint")) { CommitService(get(), fallbackMessage = "fix(lint): apply android lint fixes") }
    single(named("lint")) { BranchService(get(), branchPrefix = "androidlint") }
    single {
        LintFixService(
            get(),                       // LintPromptBuilder
            get(named("lint")),          // CommitService
            get(),                       // GitCli
            get(),                       // LintFixAgent
            get(),                       // LintScanService
            get(),                       // EditorSessionManager
            get(),                       // WorkflowProgressListener
        )
    }
    single {
        LintWorkflowRunner(
            get(),                       // LintRunContextFactory
            get(),                       // RepositorySyncService
            get(),                       // LintScanService
            get(),                       // LintSummaryPrinter
            get(named("lint")),          // BranchService
            get(),                       // LintFixService
            get(),                       // BuildValidationService
            get(),                       // PullRequestPublishService
        )
    }
}
