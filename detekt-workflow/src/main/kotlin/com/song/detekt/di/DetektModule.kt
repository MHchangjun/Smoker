package com.song.detekt.di

import com.song.detekt.DetektFixService
import com.song.detekt.DetektPhase
import com.song.detekt.DetektPromptBuilder
import com.song.detekt.DetektRunContextFactory
import com.song.detekt.DetektScanService
import com.song.workflow.CommitService
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

fun detektModule(): Module = module {
    single { DetektRunContextFactory() }
    single { DetektScanService() }
    single { DetektPromptBuilder() }
    single(named("detekt")) { CommitService(get(), fallbackMessage = "fix(detekt): apply lint fixes") }
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
    single { DetektPhase(get(), get()) }
}
