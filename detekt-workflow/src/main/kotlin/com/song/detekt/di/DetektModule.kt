package com.song.detekt.di

import com.song.detekt.*
import org.koin.core.module.Module
import org.koin.dsl.module

fun detektModule(): Module = module {
    single { DetektRunContextFactory() }
    single { DetektScanService() }
    single { DetektSummaryPrinter() }
    single { DetektPromptBuilder() }
    single { DetektCommitService(get()) }
    single { DetektBranchService(get()) }
    single { DetektFixService(get(), get(), get(), get()) }
    single { BuildValidationService(get()) }
    single { DetektWorkflowRunner(get(), get(), get(), get(), get(), get(), get(), get()) }
}
