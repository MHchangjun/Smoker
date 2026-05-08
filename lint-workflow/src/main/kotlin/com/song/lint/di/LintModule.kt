package com.song.lint.di

import com.song.lint.LintFixService
import com.song.lint.LintPhase
import com.song.lint.LintPromptBuilder
import com.song.lint.LintRunContextFactory
import com.song.lint.LintScanService
import com.song.lint.LintXmlParser
import com.song.workflow.CommitService
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

fun lintModule(): Module = module {
    single { LintRunContextFactory() }
    single { LintXmlParser() }
    single { LintScanService(get()) }
    single { LintPromptBuilder() }
    single(named("lint")) { CommitService(get(), fallbackMessage = "fix(lint): apply android lint fixes") }
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
    single { LintPhase(get(), get(), get()) }
}
